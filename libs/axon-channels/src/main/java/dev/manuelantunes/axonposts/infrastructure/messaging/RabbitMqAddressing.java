package dev.manuelantunes.axonposts.infrastructure.messaging;

import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class RabbitMqAddressing implements ChannelAddressing {
    private static final Logger log = LoggerFactory.getLogger(RabbitMqAddressing.class);

    @Override
    public String connector() {
        return "smallrye-rabbitmq";
    }

    @Override
    public Metadata addressing(EventAddress address) {
        String routingKey = address.qualifiedName() + "." + address.orderingKey();
        log.debug("routing key = {}  (tags: {})", routingKey, address.tags());
        return Metadata.of(OutgoingRabbitMQMetadata.builder()
                .withRoutingKey(routingKey)
                .withHeader("axon-message-type", address.messageType())
                .withHeader("axon-message-id", address.identifier())
                .build());
    }
}
