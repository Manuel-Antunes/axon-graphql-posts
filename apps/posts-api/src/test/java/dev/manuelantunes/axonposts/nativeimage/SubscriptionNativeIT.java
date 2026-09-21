package dev.manuelantunes.axonposts.nativeimage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.support.GraphQl;
import dev.manuelantunes.axonposts.support.SseSubscriptions;
import dev.manuelantunes.axonposts.support.WebSocketSubscriptions;
import dev.manuelantunes.axonposts.support.RequiresNativeArtifact;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.RestAssured;

@QuarkusIntegrationTest
@RequiresNativeArtifact
class SubscriptionNativeIT {
    private static final String ON_CREATED = "subscription { onPostCreated { title version } }";
    private static final String ON_UPDATED = "subscription { onPostUpdated { title version } }";
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test
    void sseKeepsDeliveringAfterTheFirstEvent() {
        GraphQl author = GraphQl.asAuthor();

        try (SseSubscriptions subscription =
                SseSubscriptions.subscribe(RestAssured.port, ON_CREATED, Map.of())) {
            author.createPost("sse-um", "c");
            author.createPost("sse-dois", "c");
            author.createPost("sse-tres", "c");

            assertThat(subscription.next("onPostCreated.title", String.class, WAIT)).isEqualTo("sse-um");
            assertThat(subscription.next("onPostCreated.title", String.class, WAIT)).isEqualTo("sse-dois");
            assertThat(subscription.next("onPostCreated.title", String.class, WAIT)).isEqualTo("sse-tres");
        }
    }

    @Test
    void webSocketKeepsDeliveringAfterTheFirstEvent() {
        GraphQl author = GraphQl.asAuthor();

        try (WebSocketSubscriptions subscription =
                WebSocketSubscriptions.subscribe(RestAssured.port, ON_CREATED, Map.of())) {
            author.createPost("ws-um", "c");
            author.createPost("ws-dois", "c");
            author.createPost("ws-tres", "c");

            assertThat(subscription.next("onPostCreated.title", String.class, WAIT)).isEqualTo("ws-um");
            assertThat(subscription.next("onPostCreated.title", String.class, WAIT)).isEqualTo("ws-dois");
            assertThat(subscription.next("onPostCreated.title", String.class, WAIT)).isEqualTo("ws-tres");
        }
    }

    @Test
    void theDefaultTagArrivesOnCreatedAndTheEditOnUpdated() {
        GraphQl author = GraphQl.asAuthor();

        try (WebSocketSubscriptions completed =
                        WebSocketSubscriptions.subscribe(RestAssured.port, ON_CREATED, Map.of());
                WebSocketSubscriptions edited =
                        WebSocketSubscriptions.subscribe(RestAssured.port, ON_UPDATED, Map.of())) {
            String id = author.createPost("upd-nativo", "c");

            assertThat(completed.next("onPostCreated.version", Integer.class, WAIT))
                    .as("a tag padrão completa o post, e é onPostCreated que anuncia a v2")
                    .isEqualTo(2);

            author.execute("mutation E($id: ID!) { updatePost(input: {id: $id, title: \"v3\"}) { version } }",
                    "id", id);

            assertThat(edited.next("onPostUpdated.version", Integer.class, WAIT))
                    .as("onPostUpdated só reage a PostUpdatedEvent: leva o post completo da v2 para a v3")
                    .isEqualTo(3);
        }
    }
}
