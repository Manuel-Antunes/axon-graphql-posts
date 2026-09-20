/**
 * A SAGA COREOGRAFADA, ponta a ponta e ENTRE PROCESSOS.
 *
 * Por que isto não é um `@QuarkusTest`: porque o que ele prova é justamente o que um `@QuarkusTest`
 * não consegue montar — dois PROCESSOS, com event stores separados, conversando por um broker de
 * verdade. Dentro de uma JVM só, "dois serviços" seria dublagem, e a dublagem é exatamente o que a
 * suíte do `posts-api` já faz (`InProcessTagAssignment`), de propósito e declaradamente.
 *
 * Os testes são ORDENADOS e compartilham estado, e isso é o desenho: uma saga é uma narrativa, e
 * afirmar o passo 4 sem ter feito o 1 não afirma nada. O Vitest roda um arquivo em ordem de
 * declaração — é o que sustenta a forma.
 */
import { beforeAll, describe, expect, it } from "vitest";
import { AxonEnvelope } from "../support/broker";
import { ChoreographyStack } from "../support/choreography-stack";
import type { AuthenticatedApi, SseSubscription } from "../support/posts-api";
import { sleep } from "../support/posts-api";

const stack = new ChoreographyStack();

interface Post {
  id: string;
  version: number;
  tags: { edges: Array<{ node: { name: string } }> };
}

const tagsOf = (post: Post): string[] => post.tags.edges.map((edge) => edge.node.name);

/** O que o `posts-api` publica e o que ele recebe de volta — os dois nomes de fio da saga. */
const PRE_CREATED = "posts.PostPreCreated";
const CREATED = "posts.PostCreated";
const UPDATED = "posts.PostUpdated";

describe("a saga coreografada entre posts-api e tagging", () => {
  let author: AuthenticatedApi;
  let subscription: SseSubscription<{ onPostCreated: Post }>;
  let postId: string;
  let completed: { onPostCreated: Post };

  beforeAll(async () => {
    author = await stack.api.asAuthor();
    subscription = await stack.api.subscribe<{ onPostCreated: Post }>(
      "subscription { onPostCreated { id version tags { edges { node { name } } } } }",
    );
    // O primeiro evento só é entregue a quem já estava no fio: dar à conexão o tempo de se
    // estabelecer antes da mutation é o que separa "não emitiu" de "emitiu antes de eu ouvir".
    await sleep(500);
  });

  it("o createPost responde PRÉ-CRIADO — versão 1, sem tag", async () => {
    const { createPost } = await author.mutate<{ createPost: Post }>(
      'mutation { createPost(input: { title: "Saga coreografada", content: "c" })'
      + " { id version tags { edges { node { name } } } } }",
    );
    postId = createPost.id;

    // A ASSINATURA de que o tagueamento saiu do fluxo da escrita. Se respondesse 2, o trabalho
    // estaria sendo feito em processo e a coreografia seria decorativa.
    expect(createPost.version, "v2 aqui significa tagueamento em processo").toBe(1);
    expect(tagsOf(createPost)).toEqual([]);
  });

  it("a subscription recebe o post COMPLETO depois da volta da saga", async () => {
    // A volta INTEIRA: posts-api -> RabbitMQ -> tagging -> RabbitMQ -> posts-api -> SSE.
    // Quando ela não fecha, a resposta está no log do `tagging` — por isso ele vai na mensagem.
    completed = await subscription.awaitMatching(
      (event) => event.onPostCreated?.id === postId && event.onPostCreated?.version === 2,
      { describeFailure: () => stack.tagging.tail() },
    );
    subscription.close();

    expect(tagsOf(completed.onPostCreated)).toContain("Untagged");
  });

  it("o event store de CADA serviço tem exatamente os eventos esperados", () => {
    /*
     * Os DOIS serviços têm os mesmos dois eventos, e cada um produziu UM deles:
     *   posts-api  produziu o PostPreCreated e INGERIU o PostCreated
     *   tagging    INGERIU o PostPreCreated e produziu o PostCreated
     *
     * É essa simetria que prova a integração — o evento que chega é APENDADO, não só processado.
     * E é aqui que um laço de reenvio apareceria, como contagem crescendo.
     */
    const expected = `${PRE_CREATED},${CREATED}`;
    expect(stack.postsStore.streamOf(postId)).toBe(expected);
    expect(stack.taggingStore.streamOf(postId), "a fila não é a fonte: o store dele é").toBe(expected);
  });

  it("o inbox registrou uma linha por mensagem recebida, com a ORIGEM certa", () => {
    expect(stack.postsStore.inbox()).toContain(CREATED);
    expect(stack.postsStore.inbox()).toContain("axonposts-tagging");
    expect(stack.taggingStore.inbox()).toContain(PRE_CREATED);
    expect(stack.taggingStore.inbox()).toContain("quarkus-axon-graphql-posts");
  });

  it("reentregar a MESMA mensagem não produz uma segunda decisão", async () => {
    const identity = stack.postsStore.identityOf(postId, PRE_CREATED);
    const routing = await stack.broker.publish(
      `${PRE_CREATED}.${postId}`,
      AxonEnvelope.of({
        identity,
        origin: "quarkus-axon-graphql-posts",
        tag: { key: "postId", value: postId },
        payload: {
          postId,
          title: "Saga coreografada",
          content: "c",
          authorId: "00000000-0000-0000-0000-000000000000",
          occurredAt: new Date().toISOString(),
        },
      }),
    );
    expect(routing.routed, "o broker não roteou: o binding mudou").toBe(true);

    await sleep(4000);
    // As três guardas cobrem coisas diferentes, e esta afirmação passa pelas duas que sobrevivem
    // a um inbox limpo: o inbox descarta a reentrega, e o agregado descarta a decisão repetida.
    expect(stack.taggingStore.countEvents(postId, CREATED)).toBe(1);
    expect(stack.taggingStore.inboxRowsFor(identity.identifier)).toBe(1);
  });

  it("o canal de RÉPLICA mantém o stream do Post completo no outro serviço", async () => {
    /*
     * O serviço de tagueamento não REAGE a PostUpdated — mas precisa TER o evento, porque ele
     * escreve no stream do Post e a posição de um append vem de ter lido o stream antes. Um canal
     * só para isso, com routing keys próprias, é o que a versão de canal único não expressava.
     */
    const { updatePost } = await author.mutate<{ updatePost: { version: number } }>(
      'mutation Editar($id: ID!) { updatePost(input: { id: $id, title: "Saga editada" }) { version } }',
      { id: postId },
    );
    expect(updatePost.version).toBe(3);

    const expected = `${PRE_CREATED},${CREATED},${UPDATED}`;
    await expect
      .poll(() => stack.taggingStore.streamOf(postId), { timeout: 20_000, interval: 500 })
      .toBe(expected);
  });
});
