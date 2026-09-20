package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ChannelEventIngestion {
    private static final Logger log = LoggerFactory.getLogger(ChannelEventIngestion.class);

    private final Configuration axon;
    private final MessageInbox inbox;
    private final ObjectMapper json;
    private final String applicationName;

    @Inject
    ChannelEventIngestion(Configuration axon, MessageInbox inbox, ObjectMapper json,
            @ConfigProperty(name = "quarkus.application.name") String applicationName) {
        this.axon = axon;
        this.inbox = inbox;
        this.json = json;
        this.applicationName = applicationName;
    }

    ChannelEventIngestion() {
        this(null, null, null, null);
    }

    public void ingest(byte[] body) throws IOException {
        try {
            ingestEnvelope(body);
        } catch (RuntimeException | IOException failure) {
            log.error("inbox ← falha ao ingerir a mensagem; ela será REJEITADA e perdida. Corpo ({} bytes): {}",
                    body.length, new String(body, java.nio.charset.StandardCharsets.UTF_8), failure);
            throw failure;
        }
    }

    private void ingestEnvelope(byte[] body) throws IOException {
        AxonEventEnvelope envelope = json.readValue(body, AxonEventEnvelope.class);
        String origin = envelope.metadata().get(ChannelMetadata.ORIGIN);

        if (applicationName.equals(origin)) {
            log.debug("inbox ← {} ({}) descartado: eco do próprio serviço",
                    envelope.messageType(), envelope.identifier());
            return;
        }

        EventMessage event = reconstitute(envelope);
        Set<Tag> tags = ChannelMetadata.tagsOf(envelope.tags());

        unitOfWorkFactory().create("axon-inbox").executeWithResult(context -> {
            if (!inbox.register(envelope.identifier(), envelope.messageType(), origin)) {
                log.info("inbox ← {} ({}) descartado: já ingerido antes",
                        envelope.messageType(), envelope.identifier());
                return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
            }
            log.debug("inbox ← {} ({}) de '{}' → apendando no event store local",
                    envelope.messageType(), envelope.identifier(), origin);
            return append(context, event, tags);
        }).join();
    }

    private EventMessage reconstitute(AxonEventEnvelope envelope) {
        Map<String, String> metadata = new LinkedHashMap<>(envelope.metadata());
        metadata.put(ChannelMetadata.TAGS, ChannelMetadata.encodeTags(envelope.tags()));
        return new GenericEventMessage(
                envelope.identifier(),
                MessageType.fromString(envelope.messageType()),
                envelope.decodePayload(),
                metadata,
                envelope.timestamp());
    }

    private java.util.concurrent.CompletableFuture<Void> append(
            org.axonframework.messaging.core.unitofwork.ProcessingContext context,
            EventMessage event, Set<Tag> tags) {
        if (tags.isEmpty()) {
            log.warn("inbox ← {} sem tags: apendando sem fronteira de agregado", event.type());
            return eventStore().publish(context, event);
        }
        EventStoreTransaction transaction = eventStore().transaction(context);
        return transaction
                .source(SourcingCondition.conditionFor(EventCriteria.havingTags(tags)))
                .reduce(0L, (count, entry) -> count + 1L)
                .thenAccept(sourced -> {
                    log.debug("inbox ← o stream de {} tinha {} evento(s); apendando em seguida",
                            tags, sourced);
                    transaction.appendEvent(event);
                });
    }

    private volatile EventStore store;
    private volatile UnitOfWorkFactory unitsOfWork;

    private UnitOfWorkFactory unitOfWorkFactory() {
        UnitOfWorkFactory resolved = unitsOfWork;
        if (resolved == null) {
            resolved = axon.getComponent(UnitOfWorkFactory.class);
            unitsOfWork = resolved;
        }
        return resolved;
    }

    private EventStore eventStore() {
        EventStore resolved = store;
        if (resolved == null) {
            resolved = axon.getComponent(EventStore.class);
            store = resolved;
        }
        return resolved;
    }
}
