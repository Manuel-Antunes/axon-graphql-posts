package dev.manuelantunes.axonposts.domain.user.event;

import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

@Event(namespace = "users", name = "UserDeleted", version = "1.0.0")
public record UserDeletedEvent(
        @EventTag UserId userId,
        Instant occurredAt
) implements DomainEvent {
}
