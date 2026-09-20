package dev.manuelantunes.axonposts.infrastructure.messaging;

import org.eclipse.microprofile.reactive.messaging.Metadata;

public interface ChannelAddressing {
    String connector();

    Metadata addressing(EventAddress address);
}
