package dev.manuelantunes.axonposts.e2e;

import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import dev.manuelantunes.axonposts.support.KeycloakContainerConfig;
import dev.manuelantunes.axonposts.support.SseSubscriptions;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * A "newsletter": subscriptions filtradas por autor, sobre SSE.
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
class NewsletterSubscriptionE2ETest extends AbstractGraphQlE2ETest {

    private static final Duration ARRIVES = Duration.ofSeconds(20);
    private static final Duration SILENCE = Duration.ofSeconds(5);

    /** O id <b>local</b> do autor: o filtro é sobre a identidade daqui, não sobre o {@code sub} do token. */
    private String localIdOf(HttpGraphQlTester tester) {
        return tester.document(
                //language=GraphQL
                "{ me { id } }").execute().path("me.id").entity(String.class).get();
    }

    /** {@code HashMap} e não {@code Map.of}: o filtro nulo é um caso legítimo (tópico global). */
    private static Map<String, Object> filter(String authorId) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", authorId);
        return variables;
    }

    private Flux<String> onPostCreatedOf(String authorId) {
        return SseSubscriptions.subscribe(port,
                //language=GraphQL
                "subscription Newsletter($id: ID) { onPostCreated(authorId: $id) { title } }",
                filter(authorId), "onPostCreated.title", String.class);
    }

    @Test
    void aSubscriberOfAnAuthorReceivesThatAuthorsPosts() {
        HttpGraphQlTester author = asAuthor();
        String authorId = localIdOf(author);

        StepVerifier.create(onPostCreatedOf(authorId))
                .then(() -> createPost(author, "Edição da semana", "conteúdo"))
                .expectNext("Edição da semana")
                .thenCancel()
                .verify(ARRIVES);
    }

    @Test
    void aSubscriberOfAnotherAuthorReceivesNothing() {
        HttpGraphQlTester author = asAuthor();
        HttpGraphQlTester outroAutor = as(KeycloakContainerConfig.PROMOTED_USERNAME);

        // provisiona os dois e assina o SEGUNDO
        localIdOf(author);
        String outroId = localIdOf(outroAutor);

        StepVerifier.create(onPostCreatedOf(outroId))
                // quem publica é o primeiro autor: o evento existe, mas não é deste tópico
                .then(() -> createPost(author, "Não é para você", "conteúdo"))
                .expectTimeout(SILENCE)
                .verify();
    }

    @Test
    void withoutAFilterEverythingArrives() {
        HttpGraphQlTester author = asAuthor();

        StepVerifier.create(onPostCreatedOf(null))
                .then(() -> createPost(author, "Tópico global", "conteúdo"))
                .expectNext("Tópico global")
                .thenCancel()
                .verify(ARRIVES);
    }

    @Test
    void onPostUpdatedAlsoFiltersByAuthorAndCatchesTheDefaultTag() {
        HttpGraphQlTester author = asAuthor();
        String authorId = localIdOf(author);

        Flux<Integer> versions = SseSubscriptions.subscribe(port,
                //language=GraphQL
                "subscription Edicoes($id: ID) { onPostUpdated(authorId: $id) { version } }",
                filter(authorId), "onPostUpdated.version", Integer.class);

        StepVerifier.create(versions)
                // criar dispara PostCreated e, logo depois, o PostUpdated da tag padrão: versão 2
                .then(() -> createPost(author, "Com tag", "conteúdo"))
                .expectNext(2)
                .thenCancel()
                .verify(ARRIVES);
    }
}
