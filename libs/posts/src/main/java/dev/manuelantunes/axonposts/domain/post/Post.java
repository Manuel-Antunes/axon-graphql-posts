package dev.manuelantunes.axonposts.domain.post;

import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostDeletedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostPreCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostRestoredEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.exception.NotThePostAuthorException;
import dev.manuelantunes.axonposts.domain.post.vo.PostContent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import dev.manuelantunes.axonposts.domain.post.vo.PostVersion;
import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;
import dev.manuelantunes.axonposts.domain.shared.EmbeddableSoftDeletable;
import dev.manuelantunes.axonposts.domain.shared.SoftDeletable;
import dev.manuelantunes.axonposts.domain.shared.SoftDeletion;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(name = "posts")
@EventSourcedEntity(tagKey = Post.TAG_KEY)
@SQLRestriction(Post.ALIVE)
@SQLDelete(sql = "update posts set deleted_at = current_timestamp where id = ?")
public class Post implements
        SoftDeletable,
        EmbeddableSoftDeletable {
    public static final String ALIVE = SoftDeletion.COLUMN + " is null";

    public static final String TAG_KEY = "postId";

    @EmbeddedId
    @AttributeOverride(name = "value", column = @Column(name = "id", length = 36, nullable = false))
    private PostId id;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "title", length = 200, nullable = false))
    private PostTitle title;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "content", columnDefinition = "TEXT", nullable = false))
    private PostContent content;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private Author author;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Embedded
    private SoftDeletion softDeletion = new SoftDeletion();

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "version", nullable = false))
    private PostVersion version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "post_tags",
            joinColumns = @JoinColumn(name = "post_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new LinkedHashSet<>();

    protected Post() {
    }

    public static Post create(PostId id,
                              String title,
                              String content,
                              Author author,
                              Collection<Tag> initialTags,
                              Instant now,
                              DomainEventPublisher events) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(initialTags, "initialTags");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        PostPreCreatedEvent event = new PostPreCreatedEvent(
                id,
                PostTitle.of(title).value(),
                PostContent.of(content).value(),
                author.id(),
                now
        );

        events.raise(event);
        Post post = new Post(event);
        return initialTags.isEmpty() ? post : post.complete(initialTags, now, events);
    }

    public Post complete(Collection<Tag> firstTags, Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(firstTags, "firstTags");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        if (isComplete()) {
            throw new InvalidPostException("post " + id + " já está completo");
        }
        if (firstTags.isEmpty()) {
            throw new InvalidPostException("completar um post exige ao menos uma tag");
        }

        Set<Tag> resulting = new LinkedHashSet<>(this.tags);
        resulting.addAll(firstTags);

        PostCreatedEvent event = new PostCreatedEvent(
                id,
                this.title.value(),
                this.content.value(),
                this.author.id(),
                resulting.stream()
                        .map(tag -> new PostCreatedEvent.AssignedTag(tag.id().value(), tag.name().value()))
                        .toList(),
                this.version.next().value(),
                now
        );

        events.raise(event);
        on(event);
        return this;
    }

    public boolean isComplete() {
        return publishedAt != null;
    }

    public Post materializeCompletion(PostCreatedEvent event, Collection<Tag> managedTags) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(managedTags, "managedTags");
        this.tags.addAll(managedTags);
        on(event);
        return this;
    }

    public Post update(String newTitle, String newContent, Author actingAuthor,
                       Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");
        assertWrittenBy(actingAuthor);

        PostTitle resultingTitle = newTitle == null ? this.title : PostTitle.of(newTitle);
        PostContent resultingContent = newContent == null ? this.content : PostContent.of(newContent);

        if (resultingTitle.equals(this.title) && resultingContent.equals(this.content)) {
            throw new InvalidPostException("update sem mudanças: informe um title e/ou content diferente do atual");
        }

        return raiseUpdate(resultingTitle, resultingContent, this.tags, now, events);
    }

    public Post assignTag(Tag tag, Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        if (hasTag(tag.id())) {
            throw new InvalidPostException("post já tem a tag " + tag.name());
        }

        Set<Tag> resulting = new LinkedHashSet<>(this.tags);
        resulting.add(tag);
        return raiseUpdate(this.title, this.content, resulting, now, events);
    }

    public Post delete(Author actingAuthor, Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");
        assertWrittenBy(actingAuthor);

        delete(now);

        PostDeletedEvent event = new PostDeletedEvent(id, author.id(), version.next().value(), now);
        events.raise(event);
        on(event);
        return this;
    }

    public Post restore(Author actingAuthor, Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");
        assertWrittenBy(actingAuthor);

        restore();

        PostRestoredEvent event = new PostRestoredEvent(id, author.id(), version.next().value(), now);
        events.raise(event);
        on(event);
        return this;
    }

    private Post raiseUpdate(PostTitle resultingTitle,
                             PostContent resultingContent,
                             Set<Tag> resultingTags,
                             Instant now,
                             DomainEventPublisher events) {
        PostUpdatedEvent event = new PostUpdatedEvent(
                id,
                resultingTitle.value(),
                resultingContent.value(),
                this.author.id(),
                resultingTags.stream().map(t -> new PostUpdatedEvent.Tag(t.id().value(), t.name().value())).toList(),
                this.version.next().value(),
                now
        );

        events.raise(event);
        on(event);
        return this;
    }

    public boolean hasTag(TagId tagId) {
        return tags.stream().anyMatch(tag -> tag.id().equals(tagId));
    }

    public boolean hasNoTags() {
        return tags.isEmpty();
    }

    public boolean isWrittenBy(UserId authorId) {
        return author.id().equals(authorId);
    }

    private void assertWrittenBy(Author actingAuthor) {
        Objects.requireNonNull(actingAuthor, "actingAuthor");
        if (!isWrittenBy(actingAuthor.id())) {
            throw new NotThePostAuthorException(id, actingAuthor.id());
        }
    }

    @EntityCreator
    public Post(PostPreCreatedEvent event) {
        this.id = event.postId();
        this.title = PostTitle.of(event.title());
        this.content = PostContent.of(event.content());
        this.author = Author.reference(event.authorId());
        this.createdAt = event.occurredAt();
        this.updatedAt = event.occurredAt();
        this.version = PostVersion.initial();
        this.tags = new LinkedHashSet<>();
    }

    @EventSourcingHandler
    public void on(PostCreatedEvent event) {
        this.updatedAt = event.occurredAt();
        this.publishedAt = event.occurredAt();
        this.version = new PostVersion(event.version());
        replaceTags(event.tags().stream()
                .map(tag -> new TagReference(tag.tagId(), tag.name()))
                .toList());
    }

    @EventSourcingHandler
    public void on(PostUpdatedEvent event) {
        this.title = PostTitle.of(event.title());
        this.content = PostContent.of(event.content());
        this.updatedAt = event.occurredAt();
        this.version = new PostVersion(event.version());
        replaceTags(event.tags().stream()
                .map(tag -> new TagReference(tag.tagId(), tag.name()))
                .toList());
    }

    private record TagReference(String tagId, String name) {
    }

    private void replaceTags(List<TagReference> fromEvent) {
        Map<TagId, Tag> current = new LinkedHashMap<>();
        this.tags.forEach(tag -> current.put(tag.id(), tag));

        Set<Tag> next = new LinkedHashSet<>();
        fromEvent.forEach(tag -> {
            TagId tagId = TagId.of(tag.tagId());
            Tag known = current.get(tagId);
            next.add(known != null ? known : Tag.reference(tagId, TagName.of(tag.name())));
        });

        this.tags.clear();
        this.tags.addAll(next);
    }

    @EventSourcingHandler
    public void on(PostDeletedEvent event) {
        applyDeletion(event.occurredAt());
        this.updatedAt = event.occurredAt();
        this.version = new PostVersion(event.version());
    }

    @EventSourcingHandler
    public void on(PostRestoredEvent event) {
        applyRestoration();
        this.updatedAt = event.occurredAt();
        this.version = new PostVersion(event.version());
    }

    public PostId id() {
        return id;
    }

    public PostTitle title() {
        return title;
    }

    public PostContent content() {
        return content;
    }

    public Author author() {
        return author;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public PostVersion version() {
        return version;
    }

    public List<Tag> tags() {
        return List.copyOf(tags);
    }

    @Override
    public SoftDeletion softDeletion() {
        if (softDeletion == null) {
            softDeletion = new SoftDeletion();
        }
        return softDeletion;
    }

    @Override
    public Object identity() {
        return id;
    }
}
