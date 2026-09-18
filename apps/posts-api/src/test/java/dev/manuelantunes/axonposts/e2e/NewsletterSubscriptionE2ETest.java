package dev.manuelantunes.axonposts.e2e;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import io.quarkus.test.junit.QuarkusTest;
import dev.manuelantunes.axonposts.support.GraphQl;
import dev.manuelantunes.axonposts.support.Realm;
import dev.manuelantunes.axonposts.support.WebSocketSubscriptions;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A "newsletter": subscriptions filtradas por autor, sobre WebSocket.
 *
 * <h2>O que este teste prova e nenhum outro consegue</h2>
 * O filtro por {@code authorId} é avaliado no <b>emit</b>, dentro do {@code QueryUpdateEmitter} do Axon:
 * cada assinante registrado é perguntado se aquele evento lhe interessa. Não há como observar isso sem o
 * Axon rodando de verdade e sem um assinante de verdade pendurado — um teste de unidade do predicado
 * provaria a regra, não a ligação entre ela e o barramento.
 * <p>
 * O caso negativo é o que importa: um assinante de outro autor não pode receber e descartar no cliente —
 * o post não pode <b>chegar</b> nele. Numa newsletter, receber o que não se assinou é o bug.
 */
@QuarkusTest
class NewsletterSubscriptionE2ETest extends AbstractGraphQlE2ETest {

    private static final Duration ARRIVES = Duration.ofSeconds(20);
    private static final Duration SILENCE = Duration.ofSeconds(5);

    @Inject
    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    int port;

    /** O id <b>local</b> do autor: o filtro é sobre a identidade daqui, não sobre o {@code sub} do token. */
    private String localIdOf(GraphQl client) {
        return client.execute("{ me { id } }").string("me.id");
    }

    /** {@code HashMap} e não {@code Map.of}: o filtro nulo é um caso legítimo (tópico global). */
    private static Map<String, Object> filter(String authorId) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", authorId);
        return variables;
    }

    private WebSocketSubscriptions onPostCreatedOf(String authorId) {
        return WebSocketSubscriptions.subscribe(port,
                "subscription Newsletter($id: ID) { onPostCreated(authorId: $id) { title } }",
                filter(authorId));
    }

    @Test
    void aSubscriberOfAnAuthorReceivesThatAuthorsPosts() {
        GraphQl author = asAuthor();
        String authorId = localIdOf(author);

        try (WebSocketSubscriptions subscription = onPostCreatedOf(authorId)) {
            author.createPost("Edição da semana", "conteúdo");

            assertThat(subscription.next("onPostCreated.title", String.class, ARRIVES))
                    .isEqualTo("Edição da semana");
        }
    }

    /**
     * Uma subscription é um <b>stream</b>, e não um evento só.
     *
     * <h2>Por que este teste existe, e por que ele é o mais importante desta classe</h2>
     * Porque o modo de falhar é invisível. O {@code Publisher} que o {@code subscriptionQuery} do Axon
     * devolve <b>não honra demanda incremental</b>: assinado com {@code request(Long.MAX_VALUE)} ele
     * entrega tudo, mas assinado com {@code request(1)} e um {@code request(1)} a cada item — que é
     * exatamente o que o {@code SubscriptionSubscriber} do SmallRye faz — ele entrega o primeiro e nunca
     * mais nada.
     * <p>
     * O resultado é uma subscription que <b>parece</b> funcionar: o handshake completa, o primeiro evento
     * chega, a conexão fica aberta, e nada aparece no log. Todos os outros testes desta classe passavam
     * assim, porque cada um afirma sobre <b>um</b> evento. O que conserta é o
     * {@code onOverflow().buffer(...)} em {@code OnPostCreatedSubscription}: sem ele, este método falha
     * no segundo {@code next}.
     */
    @Test
    void theSameSubscriptionKeepsReceivingEventAfterEvent() {
        GraphQl author = asAuthor();

        try (WebSocketSubscriptions subscription = onPostCreatedOf(null)) {
            author.createPost("primeira", "conteúdo");
            author.createPost("segunda", "conteúdo");
            author.createPost("terceira", "conteúdo");

            assertThat(List.of(
                    subscription.next("onPostCreated.title", String.class, ARRIVES),
                    subscription.next("onPostCreated.title", String.class, ARRIVES),
                    subscription.next("onPostCreated.title", String.class, ARRIVES)))
                    .as("os três eventos, na ordem, na MESMA subscription")
                    .containsExactly("primeira", "segunda", "terceira");
        }
    }

    @Test
    void aSubscriberOfAnotherAuthorReceivesNothing() {
        GraphQl author = asAuthor();
        GraphQl outroAutor = as(Realm.PROMOTED_USERNAME);

        // provisiona os dois e assina o SEGUNDO
        localIdOf(author);
        String outroId = localIdOf(outroAutor);

        try (WebSocketSubscriptions subscription = onPostCreatedOf(outroId)) {
            // quem publica é o primeiro autor: o evento existe, mas não é deste tópico
            author.createPost("Não é para você", "conteúdo");

            assertThat(subscription.silentFor(SILENCE))
                    .as("o assinante de outro autor não pode receber nada").isTrue();
        }
    }

    @Test
    void withoutAFilterEverythingArrives() {
        GraphQl author = asAuthor();

        try (WebSocketSubscriptions subscription = onPostCreatedOf(null)) {
            author.createPost("Tópico global", "conteúdo");

            assertThat(subscription.next("onPostCreated.title", String.class, ARRIVES))
                    .isEqualTo("Tópico global");
        }
    }

    /**
     * A tag padrão chega em {@code onPostCreated}, e não mais em {@code onPostUpdated}.
     *
     * <h3>O que este teste afirmava antes, e por que mudou</h3>
     * Ele esperava um {@code onPostUpdated} na versão 2: criar disparava {@code PostCreated} (versão 1,
     * sem tag) e a atribuição da tag vinha como um {@code PostUpdated}. Eram dois eventos e duas
     * emissões para um post nascer.
     * <p>
     * Agora o ciclo de criação tem duas fases dentro dele: {@code PostPreCreated} (o post existe) e
     * {@code PostCreated} (está completo, com a primeira tag). Tagueamento inicial deixou de ser um
     * <i>update</i> — e é o significado certo: ninguém editou o post, ele acabou de ficar pronto.
     * <p>
     * Para quem observa, a mudança é boa: {@code onPostCreated} passa a emitir o post <b>inteiro</b>,
     * na versão 2, e não é mais preciso assinar duas subscriptions para ver um post nascer.
     * {@code onPostUpdated} volta a significar edição de verdade — que é o que
     * {@link #onPostUpdatedFiresOnARealEdit()} cobre.
     */
    @Test
    void onPostCreatedCarriesTheDefaultTagAtVersionTwo() {
        GraphQl author = asAuthor();
        String authorId = localIdOf(author);

        try (WebSocketSubscriptions subscription = WebSocketSubscriptions.subscribe(port,
                "subscription Nascimentos($id: ID) { onPostCreated(authorId: $id) "
                        + "{ version tags { edges { node { name } } } } }",
                filter(authorId))) {

            author.createPost("Com tag", "conteúdo");

            assertThat(subscription.next("onPostCreated.version", Integer.class, ARRIVES))
                    .as("o post é emitido já completo")
                    .isEqualTo(2);
        }
    }

    /**
     * E {@code onPostUpdated} continua valendo para o que ele sempre significou: alguém editou o post.
     * Sem este teste, a mudança acima poderia estar escondendo que a subscription de edição parou de
     * funcionar.
     */
    @Test
    void onPostUpdatedFiresOnARealEdit() {
        GraphQl author = asAuthor();
        String authorId = localIdOf(author);
        String postId = createTaggedPost(author, "Para editar", "conteúdo");

        try (WebSocketSubscriptions subscription = WebSocketSubscriptions.subscribe(port,
                "subscription Edicoes($id: ID) { onPostUpdated(authorId: $id) { version } }",
                filter(authorId))) {

            author.execute("mutation Editar($id: ID!) { updatePost(input: {id: $id, title: \"Editado\"}) "
                    + "{ version } }", "id", postId);

            assertThat(subscription.next("onPostUpdated.version", Integer.class, ARRIVES))
                    .as("editar leva o post completo (v2) para a v3")
                    .isEqualTo(3);
        }
    }
}
