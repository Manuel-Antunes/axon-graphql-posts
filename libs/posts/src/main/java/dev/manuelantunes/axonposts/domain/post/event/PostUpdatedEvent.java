package dev.manuelantunes.axonposts.domain.post.event;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;
import java.util.List;

@Event(namespace = "posts", name = "PostUpdated", version = "1.0.0")
public record PostUpdatedEvent(
        @EventTag PostId postId,
        String title,
        String content,
        UserId authorId,
        List<Tag> tags,
        long version,
        Instant occurredAt
) implements DomainEvent {
    public PostUpdatedEvent {
        tags = List.copyOf(tags);
    }

    public record Tag(String tagId, String name) {
    }
}
