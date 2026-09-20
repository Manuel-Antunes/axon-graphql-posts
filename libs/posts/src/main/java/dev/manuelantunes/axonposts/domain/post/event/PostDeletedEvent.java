package dev.manuelantunes.axonposts.domain.post.event;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

@Event(namespace = "posts", name = "PostDeleted", version = "1.0.0")
public record PostDeletedEvent(
        @EventTag PostId postId,
        UserId authorId,
        long version,
        Instant occurredAt
) implements DomainEvent {
}
