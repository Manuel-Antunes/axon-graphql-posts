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
