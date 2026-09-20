package dev.manuelantunes.axonposts.infrastructure.messaging;

import org.eclipse.microprofile.reactive.messaging.Metadata;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class InMemoryAddressing implements ChannelAddressing {
    @Override
    public String connector() {
        return "smallrye-in-memory";
    }

    @Override
    public Metadata addressing(EventAddress address) {
        return Metadata.empty();
    }
}
