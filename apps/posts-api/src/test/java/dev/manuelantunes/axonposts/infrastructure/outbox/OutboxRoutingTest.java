package dev.manuelantunes.axonposts.infrastructure.outbox;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope;
import dev.manuelantunes.axonposts.infrastructure.messaging.EventAddress;
import dev.manuelantunes.axonposts.infrastructure.messaging.OutboxRouting;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class OutboxRoutingTest {
    @Inject
    OutboxRouting routing;

    private static EventAddress address(String qualifiedName, String tagKey, String tagValue) {
        String namespace = qualifiedName.substring(0, qualifiedName.indexOf('.'));
        return new EventAddress(qualifiedName + "#1.0.0", qualifiedName, namespace, "evt-1", tagValue,
                List.of(new AxonEventEnvelope.EventTag(tagKey, tagValue)));
    }

    @Test
    void everyPostEventLeavesByThePostOutbox() {
        assertThat(routing.routesFor(address("posts.PostPreCreated", "postId", "p-1")))
                .as("o namespace posts, declarado no @AxonOutbox de PostEventsOutbox")
                .singleElement()
                .extracting(OutboxRouting.Route::channel)
                .isEqualTo("post-events-out");
    }

    @Test
    void whatNobodyBoundDoesNotReachTheBroker() {
        assertThat(routing.routesFor(address("users.UserRegistered", "userId", "u-1")))
                .as("nenhum @AxonOutbox deste serviço declara o namespace users")
                .isEmpty();
    }

    @Test
    void theRoutingKeyEndsInTheAggregateTagOfTheEvent() {
        OutboxRouting.Route route =
                routing.routesFor(address("posts.PostCreated", "postId", "p-42")).get(0);

        assertThat(route.addressing().addressing(address("posts.PostCreated", "postId", "p-42"))
                .get(OutgoingRabbitMQMetadata.class))
                .get()
                .extracting(OutgoingRabbitMQMetadata::getRoutingKey)
                .isEqualTo("posts.PostCreated.p-42");
    }
}
