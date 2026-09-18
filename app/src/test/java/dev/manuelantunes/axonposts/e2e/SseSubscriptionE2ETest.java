package dev.manuelantunes.axonposts.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import dev.manuelantunes.axonposts.support.GraphQl;
import dev.manuelantunes.axonposts.support.Realm;
import dev.manuelantunes.axonposts.support.SseSubscriptions;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A mesma newsletter do {@link NewsletterSubscriptionE2ETest}, pela <b>outra porta</b>: GraphQL over
 * Server-Sent Events.
 *
 * <h2>O que esta classe prova, e por que ela não é cópia da de WebSocket</h2>
 * Que o transporte é <i>só</i> transporte. O schema, o filtro por tópico, a contrapressão e o
 * {@code onOverflow().buffer(...)} são os mesmos objetos — o que muda é como os bytes saem. Se um dia
 * uma subscription funcionar por um caminho e não pelo outro, a diferença está no transporte, e é aqui
 * que aparece.
 * <p>
 * Tem também um teste que não tem par do outro lado: {@link #theSamePathStillAnswersJsonToWhoDidNotAskForAStream()}.
 * A porta de SSE é <b>a mesma rota</b> {@code /graphql}, decidida pelo {@code Accept}, e o modo de
 * quebrar isso é silencioso: basta a rota nova ficar depois da de execução na ordem do Vert.x para
 * quem pede {@code text/event-stream} receber {@code 406}, ou ficar antes e engolir o POST JSON de
 * todo mundo. Nenhum dos dois aparece num teste de subscription.
 */
@QuarkusTest
class SseSubscriptionE2ETest extends AbstractGraphQlE2ETest {

    private static final Duration ARRIVES = Duration.ofSeconds(20);
    private static final Duration SILENCE = Duration.ofSeconds(5);

    @Inject
    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    int port;

    private String localIdOf(GraphQl client) {
        return client.execute("{ me { id } }").string("me.id");
    }

    /** {@code HashMap} e não {@code Map.of}: o filtro nulo é um caso legítimo (tópico global). */
    private static Map<String, Object> filter(String authorId) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", authorId);
        return variables;
    }

    private SseSubscriptions onPostCreatedOf(String authorId) {
        return SseSubscriptions.subscribe(port,
                "subscription Newsletter($id: ID) { onPostCreated(authorId: $id) { title } }",
                filter(authorId));
    }

    @Test
    void aSubscriberOfAnAuthorReceivesThatAuthorsPosts() {
        GraphQl author = asAuthor();
        String authorId = localIdOf(author);

        try (SseSubscriptions subscription = onPostCreatedOf(authorId)) {
            author.createPost("Edição da semana", "conteúdo");

            assertThat(subscription.next("onPostCreated.title", String.class, ARRIVES))
                    .isEqualTo("Edição da semana");
        }
    }

    /**
     * O irmão de {@code NewsletterSubscriptionE2ETest.theSameSubscriptionKeepsReceivingEventAfterEvent}.
     * <p>
     * O assinante de SSE pede um item de cada vez, igual ao de WebSocket, então ele cai na <b>mesma</b>
     * armadilha: o {@code Publisher} do {@code subscriptionQuery} do Axon não honra demanda incremental
     * e entregaria só o primeiro evento. A correção mora na camada de aplicação e vale para as duas
     * portas — este teste é o que prova que vale mesmo, e não por acaso.
     */
    @Test
    void theSameSubscriptionKeepsReceivingEventAfterEvent() {
        GraphQl author = asAuthor();

        try (SseSubscriptions subscription = onPostCreatedOf(null)) {
            author.createPost("primeira", "conteúdo");
            author.createPost("segunda", "conteúdo");
            author.createPost("terceira", "conteúdo");

            assertThat(List.of(
                    subscription.next("onPostCreated.title", String.class, ARRIVES),
                    subscription.next("onPostCreated.title", String.class, ARRIVES),
                    subscription.next("onPostCreated.title", String.class, ARRIVES)))
                    .as("os três eventos, na ordem, na MESMA conexão SSE")
                    .containsExactly("primeira", "segunda", "terceira");
        }
    }

    @Test
    void aSubscriberOfAnotherAuthorReceivesNothing() {
        GraphQl author = asAuthor();
        GraphQl outroAutor = as(Realm.PROMOTED_USERNAME);

        localIdOf(author);
        String outroId = localIdOf(outroAutor);

        try (SseSubscriptions subscription = onPostCreatedOf(outroId)) {
            author.createPost("Não é para você", "conteúdo");

            assertThat(subscription.silentFor(SILENCE))
                    .as("o filtro é avaliado no emit, e não depende do transporte").isTrue();
        }
    }

    /**
     * Uma query também atravessa o SSE: um {@code next} com o resultado e um {@code complete} logo
     * atrás. É o que o {@code graphql-sse} manda, e é o que permite um cliente falar <b>só</b> SSE com
     * este endpoint, sem precisar de um segundo caminho para query e mutation.
     * <p>
     * Como a operação termina, o corpo termina — daí dar para ler tudo com um {@code BodyHandlers}
     * bloqueante, que numa subscription travaria para sempre.
     */
    @Test
    void aQueryOverSseArrivesAsOneNextFollowedByComplete() throws Exception {
        asAuthor().createPost("Sai por SSE também", "conteúdo");

        String body;
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/graphql"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"query\":\"{ posts(first:1){ edges { node { title } } } }\"}"))
                    .timeout(ARRIVES)
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type").orElseThrow())
                    .startsWith("text/event-stream");
            body = response.body();
        }

        assertThat(body)
                .contains("event: next")
                .contains("Sai por SSE também")
                .contains("event: complete");
    }

    /**
     * O guarda da negociação: quem <b>não</b> pediu stream continua recebendo o JSON de sempre, no mesmo
     * caminho e com o mesmo content type. Ver o Javadoc da classe sobre por que este é o teste que a
     * ordem das rotas quebra.
     */
    @Test
    void theSamePathStillAnswersJsonToWhoDidNotAskForAStream() {
        asAuthor().createPost("Sai por JSON", "conteúdo");

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("query", "{ posts(first:1){ edges { node { title } } } }"))
                .when().post("/graphql")
                .then()
                .statusCode(200)
                .contentType("application/graphql-response+json")
                .body("data.posts.edges[0].node.title", Matchers.is("Sai por JSON"));
    }
}
