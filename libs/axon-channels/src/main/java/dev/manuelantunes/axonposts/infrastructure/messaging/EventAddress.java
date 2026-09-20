package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.List;

import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record EventAddress(
        String messageType,
        String qualifiedName,
        String namespace,
        String identifier,
        String orderingKey,
        List<AxonEventEnvelope.EventTag> tags) {
    private static final Logger log = LoggerFactory.getLogger(EventAddress.class);

    public static final String NO_AGGREGATE = "none";

    public static EventAddress of(EventMessage event, List<AxonEventEnvelope.EventTag> tags) {
        MessageType type = event.type();
        return new EventAddress(
                type.toString(),
                type.qualifiedName().fullName(),
                type.qualifiedName().namespace(),
                event.identifier(),
                orderingKeyOf(type, tags),
                tags);
    }

    private static String orderingKeyOf(MessageType type, List<AxonEventEnvelope.EventTag> tags) {
        if (tags.isEmpty()) {
            return NO_AGGREGATE;
        }
        if (tags.size() > 1) {
            log.warn("{} tem {} tags {} — a chave de ordenação será a de '{}', a primeira em ordem. "
                    + "Um store em aggregate mode não grava duas tags: confira o TagResolver.",
                    type, tags.size(), tags, tags.get(0).key());
        }
        return tags.get(0).value();
    }
}
