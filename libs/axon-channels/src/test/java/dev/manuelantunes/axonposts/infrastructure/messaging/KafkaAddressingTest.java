package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.kafka.common.header.Header;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope.EventTag;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaAddressingTest {
    private final KafkaAddressing addressing = new KafkaAddressing();

    private static EventAddress address(String qualifiedName, String orderingKey) {
        return new EventAddress(qualifiedName + "#1.0.0", qualifiedName, "posts", "msg-1",
                orderingKey, List.of(new EventTag("postId", orderingKey)));
    }

    @SuppressWarnings("unchecked")
    private static OutgoingKafkaRecordMetadata<String> metadataOf(Metadata metadata) {
        return (OutgoingKafkaRecordMetadata<String>) metadata
                .get(OutgoingKafkaRecordMetadata.class).orElseThrow();
    }

    private static String header(OutgoingKafkaRecordMetadata<String> metadata, String key) {
        Header header = metadata.getHeaders().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    void itAnswersForTheKafkaConnector() {
        assertThat(addressing.connector()).isEqualTo("smallrye-kafka");
    }

    @Test
    void theTopicIsTheQualifiedNameOfTheEvent() {
        assertThat(metadataOf(addressing.addressing(address("posts.PostPreCreated", "p-42"))).getTopic())
                .isEqualTo("posts.PostPreCreated");
    }

    @Test
    void theRecordKeyIsTheAggregateTagSoOnePostKeepsOnePartition() {
        assertThat(metadataOf(addressing.addressing(address("posts.PostCreated", "p-42"))).getKey())
                .isEqualTo("p-42");
    }

    @Test
    void anEventWithoutAnAggregateStillGetsAKey() {
        OutgoingKafkaRecordMetadata<String> metadata = metadataOf(addressing.addressing(
                new EventAddress("audit.Something#1.0.0", "audit.Something", "audit", "msg-2",
                        EventAddress.NO_AGGREGATE, List.of())));

        assertThat(metadata.getTopic()).isEqualTo("audit.Something");
        assertThat(metadata.getKey()).isEqualTo(EventAddress.NO_AGGREGATE);
    }

    @Test
    void carriesTheMessageTypeAndIdAsHeaders() {
        OutgoingKafkaRecordMetadata<String> metadata =
                metadataOf(addressing.addressing(address("posts.PostCreated", "p-1")));

        assertThat(header(metadata, "axon-message-type")).isEqualTo("posts.PostCreated#1.0.0");
        assertThat(header(metadata, "axon-message-id")).isEqualTo("msg-1");
    }
}
