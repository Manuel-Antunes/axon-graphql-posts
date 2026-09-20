package dev.manuelantunes.axonposts.domain.post.event;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

@Event(namespace = "posts", name = "PostPreCreated", version = "1.0.0")
public record PostPreCreatedEvent(
        @EventTag PostId postId,
        String title,
        String content,
        UserId authorId,
        Instant occurredAt
) implements DomainEvent {
}
