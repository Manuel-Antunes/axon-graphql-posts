package dev.manuelantunes.axonposts.domain.post;

import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostPreCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostDeletedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostRestoredEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.exception.NotThePostAuthorException;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.domain.shared.AlreadyDeletedException;
import dev.manuelantunes.axonposts.domain.shared.NotDeletedException;
import dev.manuelantunes.axonposts.domain.post.vo.PostContent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import dev.manuelantunes.axonposts.domain.post.vo.PostVersion;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import dev.manuelantunes.axonposts.testing.RecordingDomainEvents;
import dev.manuelantunes.axonposts.testing.UserFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostTest {
    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);

    private static final Tag UNTAGGED = Tag.reference(TagId.of("tag-1"), TagName.of("Untagged"));

    private static final Author AUTHOR = UserFixtures.author();

    private final RecordingDomainEvents events = new RecordingDomainEvents();

    @Test
    void createWithoutTagsRaisesOnlyPreCreatedAndLeavesThePostIncomplete() {
        PostId id = PostId.newId();

        Post post = Post.create(id, "  Título  ", " conteúdo ", AUTHOR, List.of(), T0, events);

        assertThat(events.single())
                .isEqualTo(new PostPreCreatedEvent(id, "Título", "conteúdo", UserFixtures.AUTHOR_ID, T0));
        assertThat(post.id()).isEqualTo(id);
        assertThat(post.title()).isEqualTo(PostTitle.of("Título"));
        assertThat(post.content()).isEqualTo(PostContent.of("conteúdo"));
        assertThat(post.author()).isEqualTo(AUTHOR);
        assertThat(post.createdAt()).isEqualTo(T0);
        assertThat(post.version()).isEqualTo(PostVersion.initial());
        assertThat(post.tags()).isEmpty();
        assertThat(post.hasNoTags()).isTrue();
        assertThat(post.isComplete()).as("sem tag, o post fica esperando a saga de tagueamento").isFalse();
        assertThat(post.publishedAt()).isNull();
    }

    @Test
    void createWithTagsRaisesBothEventsAndThePostIsBornComplete() {
        PostId id = PostId.newId();

        Post post = Post.create(id, "Título", "conteúdo", AUTHOR, List.of(UNTAGGED), T0, events);

        assertThat(events.raised()).containsExactly(
                new PostPreCreatedEvent(id, "Título", "conteúdo", UserFixtures.AUTHOR_ID, T0),
                new PostCreatedEvent(id, "Título", "conteúdo", UserFixtures.AUTHOR_ID,
                        List.of(new PostCreatedEvent.AssignedTag("tag-1", "Untagged")), 2, T0));
        assertThat(post.isComplete()).isTrue();
        assertThat(post.publishedAt()).isEqualTo(T0);
        assertThat(post.version()).isEqualTo(new PostVersion(2));
        assertThat(post.tags()).containsExactly(UNTAGGED);
    }

    @Test
    void completeRaisesPostCreatedAndTakesThePostToVersionTwo() {
        Post post = preCreatedPost();

        post.complete(List.of(UNTAGGED), T1, events);

        assertThat(events.single()).isEqualTo(new PostCreatedEvent(
                PostId.of("post-1"), "Título", "conteúdo", UserFixtures.AUTHOR_ID,
                List.of(new PostCreatedEvent.AssignedTag("tag-1", "Untagged")), 2, T1));
        assertThat(post.isComplete()).isTrue();
        assertThat(post.version()).isEqualTo(new PostVersion(2));
    }

    @Test
    void completingAnAlreadyCompletePostIsRejected() {
        Post post = preCreatedPost();
        post.complete(List.of(UNTAGGED), T0, events);
        events.clear();

        assertThatThrownBy(() -> post.complete(
                List.of(Tag.reference(TagId.of("tag-2"), TagName.of("Outra"))), T1, events))
                .isInstanceOf(InvalidPostException.class);
        assertThat(events.raised()).isEmpty();
    }

    @Test
    void completingWithoutAnyTagIsRejected() {
        Post post = preCreatedPost();

        assertThatThrownBy(() -> post.complete(List.of(), T1, events))
                .isInstanceOf(InvalidPostException.class);
        assertThat(events.raised()).isEmpty();
    }

    @Test
    void createWithInvalidValueRaisesNothing() {
        assertThatThrownBy(() -> Post.create(PostId.newId(), "   ", "conteúdo", AUTHOR, List.of(), T0, events))
                .isInstanceOf(InvalidPostException.class);

        assertThat(events.raised()).isEmpty();
    }

    @Test
    void createRejectsATitleLongerThanTheMaximum() {
        String tooLong = "x".repeat(PostTitle.MAX_LENGTH + 1);

        assertThatThrownBy(() -> Post.create(PostId.newId(), tooLong, "conteúdo", AUTHOR, List.of(), T0, events))
                .isInstanceOf(InvalidPostException.class);
    }

    @Test
    void updateRaisesTheEventWithTheResultingStateAndKeepsTheTags() {
        Post post = preCreatedPost();
        post.assignTag(UNTAGGED, T0, events);
        events.clear();

        post.update("Novo título", null, AUTHOR, T1, events);

        assertThat(events.single()).isEqualTo(new PostUpdatedEvent(
                post.id(), "Novo título", "conteúdo", UserFixtures.AUTHOR_ID,
                List.of(new PostUpdatedEvent.Tag("tag-1", "Untagged")), 3, T1));
        assertThat(post.title()).isEqualTo(PostTitle.of("Novo título"));
        assertThat(post.tags()).containsExactly(UNTAGGED);
        assertThat(post.version()).isEqualTo(new PostVersion(3));
    }

    @Test
    void assignTagRaisesPostUpdatedWithTheTagInTheList() {
        Post post = preCreatedPost();

        Post tagged = post.assignTag(UNTAGGED, T1, events);

        assertThat(events.single()).isEqualTo(new PostUpdatedEvent(
                post.id(), "Título", "conteúdo", UserFixtures.AUTHOR_ID,
                List.of(new PostUpdatedEvent.Tag("tag-1", "Untagged")), 2, T1));
        assertThat(tagged.tags()).containsExactly(UNTAGGED);
        assertThat(tagged.version()).isEqualTo(new PostVersion(2));
        assertThat(tagged.hasTag(TagId.of("tag-1"))).isTrue();
    }

    @Test
    void assigningTheSameTagTwiceIsRejected() {
        Post post = preCreatedPost();
        post.assignTag(UNTAGGED, T0, events);
        events.clear();

        assertThatThrownBy(() -> post.assignTag(UNTAGGED, T1, events))
                .isInstanceOf(InvalidPostException.class);

        assertThat(events.raised()).isEmpty();
    }

    @Test
    void theStateReturnedByUpdateIsTheSameAsSourcingTheRaisedEvent() {
        Post decided = preCreatedPost();
        Post sourced = preCreatedPost();

        decided.update(null, "outro conteúdo", AUTHOR, T1, events);
        sourced.on((PostUpdatedEvent) events.single());

        assertThat(decided.title()).isEqualTo(sourced.title());
        assertThat(decided.content()).isEqualTo(sourced.content());
        assertThat(decided.updatedAt()).isEqualTo(sourced.updatedAt());
        assertThat(decided.version()).isEqualTo(sourced.version());
        assertThat(decided.tags()).isEqualTo(sourced.tags());
    }

    @Test
    void updateWithoutChangesRaisesNothing() {
        Post post = preCreatedPost();

        assertThatThrownBy(() -> post.update("Título", "conteúdo", AUTHOR, T1, events))
                .isInstanceOf(InvalidPostException.class);

        assertThat(events.raised()).isEmpty();
    }

    @Test
    void applyingTheSameEventTwiceLeavesTheSameState() {
        Post post = preCreatedPost();
        post.assignTag(UNTAGGED, T1, events);
        PostUpdatedEvent event = (PostUpdatedEvent) events.single();

        post.on(event);
        post.on(event);

        assertThat(post.version()).isEqualTo(new PostVersion(2));
        assertThat(post.tags()).containsExactly(UNTAGGED);
        assertThat(post.updatedAt()).isEqualTo(T1);
    }

    @Test
    void deleteRaisesPostDeletedAndMarksTheState() {
        Post post = preCreatedPost();

        Post deleted = post.delete(AUTHOR, T1, events);

        assertThat(events.single()).isEqualTo(
                new PostDeletedEvent(post.id(), UserFixtures.AUTHOR_ID, 2, T1));
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.deletedAt()).isEqualTo(T1);
        assertThat(deleted.version()).isEqualTo(new PostVersion(2));
    }

    @Test
    void deletingTwiceRaisesNothing() {
        Post post = preCreatedPost();
        post.delete(AUTHOR, T0, events);
        events.clear();

        assertThatThrownBy(() -> post.delete(AUTHOR, T1, events))
                .isInstanceOf(AlreadyDeletedException.class);

        assertThat(events.raised()).isEmpty();
    }

    @Test
    void restoreRaisesPostRestoredAndClearsTheState() {
        Post post = preCreatedPost();
        post.delete(AUTHOR, T0, events);
        events.clear();

        Post restored = post.restore(AUTHOR, T1, events);

        assertThat(events.single()).isEqualTo(
                new PostRestoredEvent(post.id(), UserFixtures.AUTHOR_ID, 3, T1));
        assertThat(restored.isDeleted()).isFalse();
        assertThat(restored.deletedAt()).isNull();
    }

    @Test
    void restoringSomethingAliveRaisesNothing() {
        Post post = preCreatedPost();

        assertThatThrownBy(() -> post.restore(AUTHOR, T1, events))
                .isInstanceOf(NotDeletedException.class);

        assertThat(events.raised()).isEmpty();
    }

    @Test
    void applyingPostDeletedTwiceLeavesTheSameState() {
        Post post = preCreatedPost();
        post.delete(AUTHOR, T1, events);
        PostDeletedEvent event = (PostDeletedEvent) events.single();

        post.on(event);
        post.on(event);

        assertThat(post.isDeleted()).isTrue();
        assertThat(post.version()).isEqualTo(new PostVersion(2));
    }

    @Test
    void anotherAuthorCannotTouchThePost() {
        Post post = preCreatedPost();
        Author intruso = Author.reference(UserId.of("outro-autor"));

        assertThatThrownBy(() -> post.update("Editado por fora", null, intruso, T1, events))
                .isInstanceOf(NotThePostAuthorException.class);
        assertThatThrownBy(() -> post.delete(intruso, T1, events))
                .isInstanceOf(NotThePostAuthorException.class);

        assertThat(events.raised()).isEmpty();
    }

    @Test
    void ownershipIsCheckedEvenOnADeletedPost() {
        Post post = preCreatedPost();
        post.delete(AUTHOR, T0, events);
        events.clear();

        assertThatThrownBy(() -> post.restore(Author.reference(UserId.of("outro-autor")), T1, events))
                .isInstanceOf(NotThePostAuthorException.class);
        assertThat(events.raised()).isEmpty();
    }

    private Post preCreatedPost() {
        return new Post(new PostPreCreatedEvent(
                PostId.of("post-1"), "Título", "conteúdo", UserFixtures.AUTHOR_ID, T0));
    }
}
