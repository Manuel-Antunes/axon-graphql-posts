package dev.manuelantunes.axonposts.tagging.infrastructure.outbox;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope;
import dev.manuelantunes.axonposts.infrastructure.messaging.AxonOutbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
public class PostEventsOutbox {
    static final String CHANNEL = "post-events-out";

    @Produces
    @Singleton
    @AxonOutbox(channel = CHANNEL, namespaces = "posts")
    Emitter<AxonEventEnvelope> postEvents(@Channel(CHANNEL) Emitter<AxonEventEnvelope> channel) {
        return channel;
    }
}
