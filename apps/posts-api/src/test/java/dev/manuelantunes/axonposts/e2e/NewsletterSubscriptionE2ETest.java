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

@QuarkusTest
class NewsletterSubscriptionE2ETest extends AbstractGraphQlE2ETest {
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

        localIdOf(author);
        String outroId = localIdOf(outroAutor);

        try (WebSocketSubscriptions subscription = onPostCreatedOf(outroId)) {
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
