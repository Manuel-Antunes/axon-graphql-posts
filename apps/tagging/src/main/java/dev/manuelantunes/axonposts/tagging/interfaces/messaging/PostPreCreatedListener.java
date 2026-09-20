package dev.manuelantunes.axonposts.tagging.interfaces.messaging;

import java.io.IOException;

import dev.manuelantunes.axonposts.infrastructure.messaging.ChannelEventIngestion;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PostPreCreatedListener {
    static final String CHANNEL = "post-precreated-in";

    private final ChannelEventIngestion ingestion;

    PostPreCreatedListener(ChannelEventIngestion ingestion) {
        this.ingestion = ingestion;
    }

    @Blocking(ordered = false)
    @Incoming(CHANNEL)
    public void receive(byte[] body) throws IOException {
        ingestion.ingest(body);
    }
}
