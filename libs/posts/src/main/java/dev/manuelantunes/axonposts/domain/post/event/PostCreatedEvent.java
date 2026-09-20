package dev.manuelantunes.axonposts.domain.post.event;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;
import java.util.List;

@Event(namespace = "posts", name = "PostCreated", version = "2.0.0")
public record PostCreatedEvent(
        @EventTag PostId postId,
        String title,
        String content,
        UserId authorId,
        List<AssignedTag> tags,
        long version,
        Instant occurredAt
) implements DomainEvent {
    public PostCreatedEvent {
        tags = List.copyOf(tags);
    }

    public record AssignedTag(String tagId, String name) {
    }
}
