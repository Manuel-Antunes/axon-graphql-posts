package dev.manuelantunes.axonposts.domain.tag;

import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;
import dev.manuelantunes.axonposts.domain.tag.event.TagCreatedEvent;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "tags")
@EventSourcedEntity(tagKey = Tag.TAG_KEY)
public class Tag {
    public static final String TAG_KEY = "tagId";

    public static final String DEFAULT_NAME = "Untagged";

    public static final TagId DEFAULT_ID = TagId.of(
            UUID.nameUUIDFromBytes(("tag:" + DEFAULT_NAME).getBytes(StandardCharsets.UTF_8)).toString());

    @EmbeddedId
    @AttributeOverride(name = "value", column = @Column(name = "id", length = 36, nullable = false))
    private TagId id;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "name", length = 50, nullable = false, unique = true))
    private TagName name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Tag() {
    }

    public static Tag create(TagId id, String name, Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        TagCreatedEvent event = new TagCreatedEvent(id, TagName.of(name).value(), now);

        events.raise(event);
        return new Tag(event);
    }

    public static Tag reference(TagId id, TagName name) {
        Tag tag = new Tag();
        tag.id = Objects.requireNonNull(id, "id");
        tag.name = Objects.requireNonNull(name, "name");
        return tag;
    }

    public boolean isReference() {
        return createdAt == null;
    }

    @EntityCreator
    public Tag(TagCreatedEvent event) {
        this.id = event.tagId();
        this.name = TagName.of(event.name());
        this.createdAt = event.occurredAt();
    }

    public TagId id() {
        return id;
    }

    public TagName name() {
        return name;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Tag tag && id != null && id.equals(tag.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return name == null ? String.valueOf(id) : name.value();
    }
}
