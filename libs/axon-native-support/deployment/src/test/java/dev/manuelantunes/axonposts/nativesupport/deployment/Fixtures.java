package dev.manuelantunes.axonposts.nativesupport.deployment;

import java.time.Instant;
import java.util.List;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.modelling.annotation.TargetEntityId;

final class Fixtures {
    private Fixtures() {
    }

    record AssignedTag(String tagId, String name) {
    }

    record TagColour(String hex) {
    }

    record DeepTag(String tagId, TagColour colour) {
    }

    @Event(namespace = "test", name = "PostCreated", version = "1.0.0")
    record PostCreatedEvent(@EventTag String postId, List<AssignedTag> tags, Instant occurredAt) {
    }

    @Event(namespace = "test", name = "PostDeepened", version = "1.0.0")
    record PostDeepenedEvent(@EventTag String postId, DeepTag tag) {
    }

    @Command(namespace = "test", name = "CreatePost", version = "1.0.0")
    record CreatePost(@TargetEntityId String postId) {
    }

    @EventSourcedEntity(concreteTypes = {Author.class, Reader.class})
    static class User {
    }

    static class Author extends User {
    }

    static class Reader extends User {
    }

    static class PostProjection {
        @EventHandler
        void on(PostCreatedEvent event) {
        }
    }

    static class PostCommandHandler {
        @CommandHandler
        void handle(CreatePost command) {
        }
    }

    record Unrelated(String value) {
    }
}
