package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.Set;

import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.Tag;

public class ChannelTagResolver implements TagResolver {
    private final TagResolver delegate;

    public ChannelTagResolver(TagResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public Set<Tag> resolve(EventMessage event) {
        String encoded = event.metadata().get(ChannelMetadata.TAGS);
        if (encoded == null) {
            return delegate.resolve(event);
        }
        Set<Tag> carried = ChannelMetadata.decodeTags(encoded);
        return carried.isEmpty() ? delegate.resolve(event) : carried;
    }
}
