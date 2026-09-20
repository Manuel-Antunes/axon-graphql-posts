package dev.manuelantunes.axonposts.domain.user.event;

import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

@Event(namespace = "users", name = "UserRegistered", version = "1.0.0")
public record UserRegisteredEvent(
        @EventTag UserId userId,
        String email,
        String name,
        boolean author,
        String bio,
        UserId supersedes,
        Instant occurredAt
) implements DomainEvent {
    public static UserRegisteredEvent reader(UserId userId, String email, String name, Instant occurredAt) {
        return new UserRegisteredEvent(userId, email, name, false, null, null, occurredAt);
    }

    public static UserRegisteredEvent author(UserId userId, String email, String name, String bio, Instant occurredAt) {
        return new UserRegisteredEvent(userId, email, name, true, bio, null, occurredAt);
    }
}
