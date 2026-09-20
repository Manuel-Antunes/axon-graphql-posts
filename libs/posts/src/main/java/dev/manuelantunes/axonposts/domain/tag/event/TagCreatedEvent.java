package dev.manuelantunes.axonposts.domain.tag.event;

import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

@Event(namespace = "tags", name = "TagCreated", version = "1.0.0")
public record TagCreatedEvent(
        @EventTag TagId tagId,
        String name,
        Instant occurredAt
) implements DomainEvent {
}
