package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.common.header.internals.RecordHeader;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class KafkaAddressing implements ChannelAddressing {
    private static final Logger log = LoggerFactory.getLogger(KafkaAddressing.class);

    @Override
    public String connector() {
        return "smallrye-kafka";
    }

    @Override
    public Metadata addressing(EventAddress address) {
        log.debug("topic = {}  record key = {}  (tags: {})",
                address.qualifiedName(), address.orderingKey(), address.tags());
        return Metadata.of(OutgoingKafkaRecordMetadata.<String>builder()
                .withTopic(address.qualifiedName())
                .withKey(address.orderingKey())
                .addHeaders(
                        header("axon-message-type", address.messageType()),
                        header("axon-message-id", address.identifier()))
                .build());
    }

    private static RecordHeader header(String key, String value) {
        return new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8));
    }
}
