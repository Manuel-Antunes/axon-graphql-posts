package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.List;

import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope.EventTag;
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqAddressingTest {
    private final RabbitMqAddressing addressing = new RabbitMqAddressing();

    private static EventAddress address(String qualifiedName, String orderingKey) {
        return new EventAddress(qualifiedName + "#1.0.0", qualifiedName, "posts", "msg-1",
                orderingKey, List.of(new EventTag("postId", orderingKey)));
    }

    private static OutgoingRabbitMQMetadata metadataOf(Metadata metadata) {
        return metadata.get(OutgoingRabbitMQMetadata.class).orElseThrow();
    }

    @Test
    void itAnswersForTheRabbitMqConnector() {
        assertThat(addressing.connector()).isEqualTo("smallrye-rabbitmq");
    }

    @Test
    void theRoutingKeyIsQualifiedNameThenAggregateKey() {
        Metadata metadata = addressing.addressing(address("posts.PostPreCreated", "p-42"));

        assertThat(metadataOf(metadata).getRoutingKey()).isEqualTo("posts.PostPreCreated.p-42");
    }

    @Test
    void theRoutingKeyHasExactlyThreeSegmentsSoTheBindingMatches() {
        String routingKey = metadataOf(addressing.addressing(address("posts.PostCreated", "p-1")))
                .getRoutingKey();

        assertThat(routingKey.split("\\.")).hasSize(3);
    }

    @Test
    void anEventWithoutAnAggregateStillGetsThreeSegments() {
        String routingKey = metadataOf(addressing.addressing(
                new EventAddress("audit.Something#1.0.0", "audit.Something", "audit", "msg-2",
                        EventAddress.NO_AGGREGATE, List.of()))).getRoutingKey();

        assertThat(routingKey).isEqualTo("audit.Something.none");
        assertThat(routingKey.split("\\.")).hasSize(3);
    }

    @Test
    void carriesTheMessageTypeAndIdAsHeaders() {
        Metadata metadata = addressing.addressing(address("posts.PostCreated", "p-1"));

        assertThat(metadataOf(metadata).getHeaders())
                .containsEntry("axon-message-type", "posts.PostCreated#1.0.0")
                .containsEntry("axon-message-id", "msg-1");
    }

    @Test
    void theInMemoryAddressingAnswersForItsConnectorAndAddressesNothing() {
        InMemoryAddressing inMemory = new InMemoryAddressing();

        assertThat(inMemory.connector()).isEqualTo("smallrye-in-memory");
        assertThat(inMemory.addressing(address("posts.PostCreated", "p-1")))
                .isEqualTo(Metadata.empty());
    }
}
