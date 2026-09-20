package dev.manuelantunes.axonposts.domain.user.event;

import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

@Event(namespace = "users", name = "AccountLinked", version = "1.0.0")
public record AccountLinkedEvent(
        @EventTag UserId userId,
        String accountId,
        AuthProvider provider,
        String subject,
        String passwordHash,
        Instant occurredAt
) implements DomainEvent {
    public static AccountLinkedEvent federated(UserId userId, String accountId,
                                               AuthProvider provider, String subject, Instant occurredAt) {
        return new AccountLinkedEvent(userId, accountId, provider, subject, null, occurredAt);
    }
}
