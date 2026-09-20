package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventstreaming.Tag;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ChannelEventForwarder {
    private static final Logger log = LoggerFactory.getLogger(ChannelEventForwarder.class);

    private final Configuration axon;
    private final OutboxRouting routing;
    private final String applicationName;

    ChannelEventForwarder(Configuration axon, OutboxRouting routing,
            @ConfigProperty(name = "quarkus.application.name") String applicationName) {
        this.axon = axon;
        this.routing = routing;
        this.applicationName = applicationName;
    }

    private final Executor forwarding = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "axon-channel-outbox");
        thread.setDaemon(true);
        return thread;
    });

    private volatile EventConverter converter;
    private volatile TagResolver tags;

    private EventConverter converter() {
        EventConverter resolved = converter;
        if (resolved == null) {
            resolved = axon.getComponent(EventConverter.class);
            converter = resolved;
        }
        return resolved;
    }

    private TagResolver tags() {
        TagResolver resolved = tags;
        if (resolved == null) {
            resolved = axon.getComponent(TagResolver.class);
            tags = resolved;
        }
        return resolved;
    }

    private List<AxonEventEnvelope.EventTag> tagsOf(EventMessage event) {
        Set<Tag> resolved = tags().resolve(event);
        return resolved.stream()
                .sorted(Comparator.comparing(Tag::key).thenComparing(Tag::value))
                .map(tag -> new AxonEventEnvelope.EventTag(tag.key(), tag.value()))
                .toList();
    }

    @PreDestroy
    void shutdown() {
        if (forwarding instanceof java.util.concurrent.ExecutorService service) {
            service.shutdownNow();
        }
    }

    public CompletableFuture<Void> forward(EventMessage event) {
        String origin = event.metadata().get(ChannelMetadata.ORIGIN);
        if (origin != null) {
            log.debug("channel ← {} ({}) NÃO encaminhado: veio de '{}'",
                    event.type(), event.identifier(), origin);
            return CompletableFuture.completedFuture(null);
        }

        List<AxonEventEnvelope.EventTag> eventTags = tagsOf(event);
        EventAddress address = EventAddress.of(event, eventTags);
        List<OutboxRouting.Route> routes = routing.routesFor(address);
        if (routes.isEmpty()) {
            log.debug("channel ← {} ({}) não casa com nenhum outbox deste serviço",
                    address.qualifiedName(), address.identifier());
            return CompletableFuture.completedFuture(null);
        }

        Map<String, String> metadata = new LinkedHashMap<>(event.metadata());
        metadata.put(ChannelMetadata.ORIGIN, applicationName);
        AxonEventEnvelope envelope = new AxonEventEnvelope(
                address.messageType(),
                address.identifier(),
                event.timestamp(),
                metadata,
                eventTags,
                AxonEventEnvelope.encodePayload(converter().convertPayload(event, byte[].class)));

        CompletableFuture<?>[] sent = new CompletableFuture<?>[routes.size()];
        for (int destination = 0; destination < routes.size(); destination++) {
            OutboxRouting.Route route = routes.get(destination);
            log.debug("channel ← {} ({}) → {}",
                    envelope.messageType(), envelope.identifier(), route.channel());
            sent[destination] = route.send(envelope, address);
        }

        return CompletableFuture.allOf(sent).thenApplyAsync(ignored -> (Void) null, forwarding);
    }
}
