package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.List;

import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.meks.quarkiverse.axon.runtime.customizations.EventDispatchInterceptorsProducer;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ChannelEventDispatchInterceptor implements EventDispatchInterceptorsProducer {
    private static final Logger log = LoggerFactory.getLogger(ChannelEventDispatchInterceptor.class);

    private final ChannelEventForwarder forwarder;
    private final boolean publishEvents;

    ChannelEventDispatchInterceptor(ChannelEventForwarder forwarder,
            @ConfigProperty(name = "axonposts.messaging.publish-events") boolean publishEvents) {
        this.forwarder = forwarder;
        this.publishEvents = publishEvents;
    }

    @Override
    public List<MessageDispatchInterceptor<EventMessage>> createDispatchInterceptor() {
        if (!publishEvents) {
            log.info("integração com channels desligada (axonposts.messaging.publish-events=false)");
            return List.of();
        }
        log.info("todo evento despachado será oferecido aos outboxes deste serviço depois do commit");
        return List.of((event, context, chain) -> {
            if (context != null) {
                context.onAfterCommit(committed -> forwarder.forward(event));
            }
            return chain.proceed(event, context);
        });
    }
}
