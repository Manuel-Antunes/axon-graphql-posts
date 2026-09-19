# `apps/web` — o cliente de teste

Um Next.js (App Router) que existe para **exercitar a API**, não para ser um produto. Cada página
corresponde a uma decisão de arquitetura do `axonposts` e mostra o resultado como ele é — inclusive
quando o resultado é que alguma coisa não atravessa.

```bash
pnpm install                      # da raiz: o app entra no workspace pnpm
pnpm --filter @axonposts/web dev  # http://localhost:3000
pnpm dev                          # da raiz: sobe os DOIS backends e este front (nx run-many)
```

O `.env.local` aponta para a stack de `dev` na AWS. Para rodar contra o backend local, troque
`NEXT_PUBLIC_GRAPHQL_URL` para `http://localhost:8080/graphql`. **Não é preciso CORS**: o navegador
nunca fala com a API — ele fala com `/api/graphql`, o proxy desta aplicação (ver abaixo), e quem
atravessa a fronteira é o servidor do Next.

O CORS que existe no API Gateway (`infra/aws/support/http-api.ts`) deixou de ser necessário para este
cliente quando o proxy entrou. Ele ficou para quem quiser bater na API direto de um navegador.

---

## A regra que decide onde cada coisa mora

> **Organização de arquivos é por FEATURE. Atomic design é por COMPOSIÇÃO.**

Não há pasta `atoms/`, `molecules/` nem `organisms/`. Há a rota e o que ela precisa:

```
src/app/
  _components/      o que atravessa features (post-card, post-list, tag-chip, site-header…)
  _providers/       SessionProvider + ApolloProvider
  actions/          "use server" — a ÚNICA porta entre esta aplicação e o Cognito
  feed/             page.tsx + _components/ + _hooks/
  posts/new/        page.tsx + _components/
  posts/[id]/       page.tsx + _components/ + _hooks/
  saga/  live/  me/  federation/  login/
src/components/ui/  as primitivas do shadcn (base-nova, sobre @base-ui/react)
src/lib/            o que não é React: apollo/, auth/, env.ts
src/gql/            GERADO pelo codegen. Não editar, não versionar.
```

O `_` na frente do diretório é o que o App Router usa para dizer "isto não é rota". É por isso que
`_components` e `_hooks` podem morar dentro de `feed/` sem criar `/feed/_components` como URL.

A escala atômica continua existindo — ela só não é uma pasta. `TagChip` é um átomo, `AuthorByline` é
uma molécula, `PostCard` é um organismo, e a diferença entre eles está em **quanto** cada um pede ao
schema, não em onde o arquivo está.

---

## Fragment-based props: o over-fetching como erro de compilação

O `client-preset` do graphql-codegen gera *fragment masking*. Na prática:

```tsx
export const TagChip_tag = graphql(`
    fragment TagChip_tag on Tag { id name }
`);

export function TagChip({ tag }: { tag: FragmentType<typeof TagChip_tag> }) {
    const { name } = getFragmentData(TagChip_tag, tag);   // só `id` e `name` existem aqui
    return <Badge>#{name}</Badge>;
}
```

`FragmentType<...>` é **opaco**. Mesmo que a query da página tenha trazido o post inteiro, o
TypeScript recusa este componente ler qualquer campo fora do fragmento dele. E a página, do outro
lado, não enumera campo nenhum:

```graphql
query FeedPosts($first: Int!, $after: String) {
    posts(first: $first, after: $after) { ...PostList_connection }
}
```

A demonstração mais direta disso está no par `PostCard_post` / `PostArticle_post`: **o card não pede
`content`**. Um feed de 20 posts que trouxesse o corpo de cada um transferiria o blog inteiro para
desenhar 20 títulos. Quem pede `content` é a página do post.

`unmaskFunctionName: "getFragmentData"` e não o default `useFragment`, porque o Apollo 4 tem um hook
`useFragment` com outro significado (ler um fragmento do cache). Duas coisas com o mesmo nome num
import seriam confusão permanente — e esta não é um hook, é uma função pura.

---

## `possibleTypes`: o que o cache não consegue deduzir

O `InMemoryCache` faz casamento de fragmentos **heuristicamente**. Ao ler `... on Author` de um
objeto do cache, ele não tem como saber se aquele objeto é um `Author` — a resposta só traz
`__typename`, e nada diz que `Author` implementa `User`. Sem ajuda, ele assume que casa.

Neste schema isso não é hipotético: `me` devolve a **interface** `User` (com `Author` e `Reader`), e
`_entities` devolve a **união** `_Entity`. O mapa vem do plugin `fragment-matcher`:

```ts
// src/lib/apollo/cache.ts
import generatedIntrospection from "@/gql/possible-types";

new InMemoryCache({
    possibleTypes: generatedIntrospection.possibleTypes,
    typePolicies: { Query: { fields: { posts: relayStylePagination() } } },
});
```

Gerado, e não escrito à mão, porque um tipo novo que implemente `User` tem de aparecer ali sem
ninguém lembrar.

---

## As queries das páginas: `query.ts` + `PreloadQuery`

> **A query é da PÁGINA, não do hook.** Ela mora num `query.ts` ao lado do `page.tsx`, e os dois
> lados a leem do mesmo lugar.

```tsx
// app/feed/page.tsx  (server component)
<PreloadQuery query={FeedPostsQuery} variables={{ first: FEED_PAGE_SIZE }} errorPolicy="all">
  <Suspense fallback={<FeedSkeleton />}>
    <FeedView />
  </Suspense>
</PreloadQuery>
```

```ts
// app/feed/_hooks/use-post-feed.ts  (client)
const { data, error, fetchMore } = useSuspenseQuery(FeedPostsQuery, {
  variables: { first: FEED_PAGE_SIZE },   // as MESMAS variáveis, ou o cache não casa
  errorPolicy: "all",
});
```

O prefetch roda no servidor com o cliente de `lib/apollo/rsc.ts`, que lê o ID token do cookie
`httpOnly` e o põe no header — então até uma página autenticada (`/me`) renderiza no servidor sem o
token passar pelo JavaScript. O resultado atravessa pelo stream do React (é para isso que o
`ApolloNextAppProvider` e o `InMemoryCache` de `@apollo/client-integration-nextjs` existem), e o
`useSuspenseQuery` o encontra no cache em vez de pedir de novo.

Três consequências que valem a pena saber:

- **as variáveis são constantes compartilhadas** (`FEED_PAGE_SIZE`, `LIVE_SNAPSHOT_SIZE`). Se o
  servidor pedir 6 e o cliente 10, não há erro — há uma segunda requisição, e só a aba de rede conta;
- **`errorPolicy: "all"` dos dois lados.** Com o default, um erro vira exceção no render do RSC e a
  rota inteira cai. Nesta aplicação o erro é conteúdo: ele aparece no lugar da lista, com o `code`
  que o servidor traduziu;
- **`refetch` vai dentro de `startTransition`.** Sem isso, recarregar um componente que suspende
  desmonta a árvore e o `fallback` volta — a lista pisca.

Nem toda página tem prefetch, e as exceções dizem o porquê: `/saga` mede um post que ainda não
existe quando a página renderiza (prefetchar uma medição a falsificaria) e as subscriptions de
`/live` não terminam, e `PreloadQuery` executa operações que terminam.

---

## O proxy: `/api/graphql`

> **O navegador só conhece um endereço, e ele é da própria aplicação.**

```
navegador ──► /api/graphql ──► posts-api      (app/api/graphql/route.ts)
servidor  ─────────────────► posts-api        (PreloadQuery, lib/apollo/rsc.ts)
```

Três coisas que isso resolve, e nenhuma é "organizar melhor":

1. **O ID token para de existir no navegador.** Ele nasce numa server action, mora num cookie
   `httpOnly` e é o *proxy* quem o põe no header `Authorization`. Antes ele precisava chegar à memória
   da página para o `authLink` do Apollo montá-lo; `lib/apollo/token.ts` e `links/auth-link.ts`
   sumiram por causa disso, e o tipo `Session` que desce para o cliente não tem mais o campo.
2. **Não há CORS no caminho.** Mesma origem: nenhum preflight.
3. **Existe UM lugar onde o streaming pode ser resolvido** — o assunto da seção seguinte.

O **servidor** não passa pelo proxy: fazer o `PreloadQuery` abrir uma conexão HTTP para a própria
aplicação custaria mais uma invocação de Lambda e latência, e lá o cookie já está à mão.

### Duas regras que vêm da topologia, não do gosto

Isto é um *loopback* de SSR: a requisição já atravessou o CloudFront desta aplicação antes de chegar
aqui, e vai atravessá-lo de novo na volta. As duas pontas têm limites.

- **Cabeçalhos por lista de PERMISSÃO, nas duas direções.** O CloudFront empilha
  `cloudfront-viewer-*`, `x-amz-cf-*`, `x-amzn-tls-*`, `via`, `x-forwarded-*` por cima dos do
  navegador; reencaminhar tudo estoura o limite da requisição que ele entrega ao gateway, e a resposta
  é uma página `403 "Bad request"` — que chega ao cliente como um 403 inexplicável, com o usuário
  autenticado. Na volta, devolver os cabeçalhos de infraestrutura do gateway faz o CloudFront recusar
  a resposta de origem com **502**, mesmo com o corpo correto.
- **O caminho JSON BUFFERIZA os dois sentidos.** `new Response(upstream.body)` relaya o
  `ReadableStream` do `fetch` pela função de streaming do Next, e o que sai de lá o CloudFront recusa
  com 502. Ler o corpo inteiro produz uma resposta bem formada e de tamanho fixo. Payload de GraphQL é
  pequeno; o custo em memória é nenhum. Quem **precisa** streamar — a subscription — constrói o
  próprio stream em vez de relayar o do `fetch`.

---

## Subscriptions: elas FUNCIONAM, e o que foi preciso

O cliente fala `subscription onPostUpdated { … }` e recebe o evento. Contra a stack em Lambda. Isso
custou consertar **três** camadas independentes, e nenhuma delas estava no front:

1. **o handler do Quarkus não streama.** `quarkus-amazon-lambda-http` é um
   `RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse>` — o tipo de retorno É a resposta
   inteira. A saída não foi forkar: foi **não usá-lo**. O perfil `-Plambda-stream` empacota a
   aplicação como o servidor HTTP que ela é, e o **AWS Lambda Web Adapter** (layer oficial) a
   transforma em função, com `AWS_LWA_INVOKE_MODE=response_stream`;
2. **o API Gateway não faz response streaming em modo nenhum.** Só Function URL com
   `InvokeMode: RESPONSE_STREAM`. É o que `StreamingFunction` cria, e é para lá que
   `NEXT_PUBLIC_GRAPHQL_URL` aponta;
3. **a fonte era em processo.** `emitUpdate` alcança os assinantes do próprio container, e em Lambda
   a mutation cai obrigatoriamente noutro. Resolvido por **configuração do Axon**, sem tocar nos
   handlers: `PostCreatedEventHandler` e `PostUpdatedEventHandler` continuam sendo o que sempre foram —
   um `emit` e mais nada —, e o pacote deles (`application.post.event`) passou a rodar num processor
   *pooled streaming*, com token store em memória e posição inicial no HEAD. Cada container tem o
   próprio cursor e lê o event store, que é o único lugar que todos enxergam.

Medido no navegador, com o painel `/live` aberto e as mutations atendidas por outro container:

```
onPostCreated  00:10:31  ao vivo no navegador          v2 · saga fechada
onPostUpdated  00:10:42  editado ao vivo no navegador  v3 · editado
```

### O proxy repassa, e só isso

`/api/graphql` é um `fetch` e um `new Response(upstream.body)`. Ele não interpreta o protocolo, não
monta frame nenhum e não sabe o que é uma subscription — o que chega ao navegador são os bytes que o
`posts-api` escreveu. A única coisa que ele acrescenta é o `Authorization`, lido do cookie `httpOnly`.

**Houve uma emulação aqui** — consulta em laço recortando a seleção do cliente para uma query
`post(id:)` —, e ela existia porque o upstream era o API Gateway, que monta a resposta inteira em
memória. Com o upstream na Function URL com response streaming, o repasse sempre funciona; um caminho
alternativo que nunca executa é um caminho que ninguém conserta, então ele saiu.

**O que segura o repasse não está neste arquivo**: é o `OriginReadTimeout` do CloudFront, que um
`sst.aws.Nextjs` solto cria em 20 s e que um cold start do `posts-api` (15–26 s, medido) estoura com
o proxy calado. Quem o resolve é o `sst.aws.Router` de `infra/aws/edge/`: com o site roteado, o SST
espelha o `timeout` do servidor no `readTimeout` da rota — 60 s, o máximo do CloudFront.
<br>
O mesmo router serve o subgraph em `/graphql`, e é isso que dará um domínio e um certificado para os
dois no dia em que houver um.

### Três números que foram medidos, não escolhidos

- **`axonposts.graphql.sse.keep-alive=2s`** no perfil `lambda`. O `SseStream` escreve o status ao
  abrir, mas o Vert.x só manda os cabeçalhos junto com o primeiro pedaço do corpo — que é o
  keep-alive. Com o default de 15 s, o proxy esperava 15 segundos para descobrir algo que já estava
  pronto;
- **timeout de 360 s** na função do Next, acima dos 300 s do `posts-api`. Quem decide o fim da conexão
  tem de ser o upstream: com um teto menor aqui, quem cortava era a AWS, e isso chega ao navegador
  como `Connection closed while having active streams`;
- **`retryAttempts: 5`** no cliente SSE. A conexão tem fim — a função do upstream tem timeout —, e
  reconectar é o que transforma um teto de invocação numa emenda que ninguém vê. (Era `0` enquanto o
  upstream nunca abria o stream: ali cada tentativa custava o prazo inteiro.)

---

## Autenticação: server actions, e o bearer é o ID token

`src/app/actions/auth.ts` é a única porta para o Cognito. O formulário faz um POST comum
(`useActionState`), a ação chama `InitiateAuth` com `USER_PASSWORD_AUTH` e guarda **os dois tokens em
cookies `httpOnly`**. O navegador nunca vê o refresh token; o ID token só chega a ele em memória,
entregue pela ação `currentSession`, e vai dali para o header `Authorization` do Apollo.

Três decisões que custam explicação:

- **`fetch`, não o `@aws-sdk/client-cognito-identity-provider`.** As duas operações usadas
  (`USER_PASSWORD_AUTH` e `REFRESH_TOKEN_AUTH`) são não autenticadas — não assinam com SigV4. O SDK
  traria ~2 MB para o bundle da função para montar um POST com dois cabeçalhos.
- **A senha não vai por `/oauth2/token`.** O endpoint OAuth2 do Cognito aceita `authorization_code`,
  `client_credentials` e `refresh_token`, e não `password`. É o mesmo caminho que o
  `infra/scripts/e2e.sh` usa.
- **O bearer é o ID token**, porque o access token do Cognito não traz `email` e o `UserProvisioning`
  exige um. O que torna isso seguro é `quarkus.oidc.token.audience`, que confere o `aud`. O Javadoc
  de `infra/aws/identity/index.ts` tem a decisão inteira.

O token é posto em `lib/apollo/token.ts` **durante o render** do provider, e não num `useEffect`: um
efeito roda depois dos efeitos dos filhos, e é um filho quem dispara a primeira query — a primeira
requisição de cada carga sairia sem `Authorization`.

---

## Build: Nx constrói, OpenNext empacota, SST publica

```
nx run web:open-next-build
  └─ dependsOn: build      →  pnpm codegen && next build   (INFRA_PROVIDER=aws ⇒ output: standalone)
  └─ open-next build                                        (buildCommand: "exit 0" — não reconstrói)
```

O `sst.aws.Nextjs` chama exatamente esse alvo (`buildCommand` em `infra/aws/web/index.ts`), o que
compra cache do Nx e uma definição só de "como se constrói o site".

Duas linhas do `next.config.ts` existem por causa disso, e nenhuma é afinamento:

- **`outputFileTracingRoot: monorepoRoot`** — num workspace pnpm, `node_modules` é um mar de symlinks
  para a raiz. Sem apontá-la, o Next escolhe um root por heurística e o bundle sai sem pacotes; o
  sintoma é `Cannot find module` em runtime, depois de um build que imprimiu sucesso;
- **`output: "standalone"` só com `INFRA_PROVIDER=aws`** — é o formato que o OpenNext empacota, e não
  há razão para pagá-lo em todo `next build` local. O acoplamento com `buildCommand: "exit 0"` do
  `open-next.config.ts` é real e está declarado nos dois arquivos.

Os `NEXT_PUBLIC_*` são **inlinados em build time**: trocar de ambiente é rebuildar, não reiniciar. É
por isso que o alvo `build` do Nx declara essas variáveis em `inputs` — sem isso o cache devolveria
um bundle apontando para o ambiente anterior.

---

## Armadilhas já pagas

- **NÃO deixe `next dev` rodando durante um deploy.** O servidor de desenvolvimento reescreve `.next`
  continuamente, e o `open-next.config.ts` deste projeto tem `buildCommand: "exit 0"` — ele não
  constrói, ele EMPACOTA o que estiver no diretório. Com o dev no ar, o que sobe é um build de
  desenvolvimento: o HTML passa a referenciar `/_next/static/chunks/main-app.js` (sem hash, que é o
  nome que só o dev usa), o S3 responde `403`, a página não hidrata, o formulário cai no POST nativo
  e a server action morre com `TypeError: a[d] is not a function` no `webpack-runtime`. Nada disso
  aponta para a causa.

- **`@graphql-typed-document-node/core` precisa ser dependência DIRETA.** O código gerado o importa, e
  o pnpm não expõe dependência transitiva. Sem a linha no `package.json`, todo `graphql()` vira
  `unknown` e o erro aparece nos componentes, longe da causa.
- **O `Button` do shadcn "base-nova" não tem `asChild`.** O estilo atual é construído sobre
  `@base-ui/react`, e a composição com outro elemento é `render={<Link … />}` — `asChild` é a API do
  Radix.
- **`useSearchParams` exige fronteira de Suspense** para o Next poder pré-renderizar o resto da
  página. Vale para `/login` e `/saga`.
- **`secure: true` no cookie mata o login local.** Em `next dev` a origem é http, e o navegador
  descarta o cookie em silêncio: o sintoma é "o login não persiste". Daí
  `secure: process.env.NODE_ENV === "production"`. Cuidado: **se o shell exportar
  `NODE_ENV=production`**, `next dev` quebra de dois jeitos (o PostCSS não roda e toda página dá 500,
  e o cookie sai `secure`). Rode com `NODE_ENV=development pnpm exec next dev`.
- **Login: três coisas competiam pela navegação, e só uma podia vencer.** O `layout.tsx` lê o cookie,
  então o payload do layout depende dele — e em produção o Next faz prefetch dos `<Link>` visíveis,
  guardando o layout ANÔNIMO enquanto o usuário ainda está na tela de login. Qualquer navegação
  **suave** para `/feed` reaproveita esse payload: cookie gravado, servidor sabendo quem é, e o
  cabeçalho dizendo "Entrar". Em `next dev` (sem prefetch) nada disso aparece. As três fontes de
  navegação suave, removidas uma a uma:
  1. `redirect()` dentro da própria ação;
  2. `revalidatePath("/", "layout")` na ação — ele revalida a rota ATUAL, `/login`;
  3. e a que sobreviveu às duas primeiras: **o Next re-renderiza a rota atual depois de TODA server
     action**, e o `page.tsx` de `/login` tinha `if (await readSession()) redirect("/feed")`. Esse
     redirect disparava sozinho, no render que vem junto com a resposta da ação, e desmontava o
     formulário antes de ele poder recarregar a página.

  O desenho final: a página de login **não redireciona** (quem já tem sessão vê um cartão dizendo
  isso), e o formulário chama `signIn` **à mão** — não por `useActionState` — para que
  `window.location.assign` rode no mesmo *tick* da resposta, sem render intermediário competindo.
  Sessão nova é documento novo.
- **`Query.posts` é ordem de criação CRESCENTE.** `posts(first: 10)` são os dez mais ANTIGOS, e um
  post criado agora entra no fim da lista. O painel de tempo real ficou mudo por isso: estava olhando
  para o outro lado. Sem `last`/`before` no schema, `/live` pede a página inteira (o teto é 100) e
  olha a cauda — com o limite declarado em `app/live/query.ts`.
- **`relayStylePagination()` sem `keyArgs` funde TODA leitura de `posts`.** Com o default
  (`keyArgs: false`), a leitura de `/live` (`first: 100`) e a do feed (`first: 6`) viram a mesma
  lista no cache, e o feed voltava com cem cards. `keyArgs: ["first"]` separa as duas sem quebrar o
  "carregar mais" (que só muda `after`).

---

## As páginas, e o que cada uma prova

| rota | o que chama | o que prova |
|---|---|---|
| `/login` | `InitiateAuth` (server action) | o bearer é o ID token; `aud` conferido pela API |
| `/feed` | `posts(first:, after:)` | cursor connection + fragmentos por componente |
| `/posts/new` | `createPost` | nasce na **v1, sem tag** — quem completa é o outro serviço |
| `/posts/[id]` | `post`, `updatePost`, `deletePost`, `restorePost` | update parcial; exclusão **lógica** |
| `/saga` | `createPost` + sonda | a travessia v1 → v2, cronometrada |
| `/live` | `onPostCreated`, `onPostUpdated`, `/live/stream` | o que não atravessa, e um SSE que atravessa |
| `/me` | `me` | interface polimórfica + `possibleTypes` + account linking |
| `/federation` | `_entities(representations:)` | resolve por chave, em lote, **sem token** |
