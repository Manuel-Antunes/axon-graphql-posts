package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.application.post.command.AssignTagToPostCommand.AssignTagToPost;
import dev.manuelantunes.axonposts.domain.post.event.PostPreCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.post.vo.PostVersion;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.exception.TagNotFoundException;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import dev.manuelantunes.axonposts.support.InMemoryPostRepository;
import dev.manuelantunes.axonposts.testing.UserFixtures;
import dev.manuelantunes.axonposts.support.InMemoryTagRepository;
import dev.manuelantunes.axonposts.support.PostCommandFixtures;
import org.axonframework.modelling.repository.EntityNotFoundException;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.manuelantunes.axonposts.support.PostCommandFixtures.FIXED_CLOCK;
import static dev.manuelantunes.axonposts.support.PostCommandFixtures.NOW;
import static dev.manuelantunes.axonposts.support.PostCommandFixtures.hasCause;
import static org.assertj.core.api.Assertions.assertThat;

/** Given-when-then do {@link AssignTagToPostCommand}, e só dele. */
class AssignTagToPostCommandTest {

    private static final TagId TAG_ID = TagId.of("tag-1");

    private InMemoryPostRepository posts;
    private InMemoryTagRepository tags;
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        posts = new InMemoryPostRepository();
        tags = new InMemoryTagRepository();
        // a tag tem de existir: o handler a carrega antes de decidir
        tags.save(Tag.reference(TAG_ID, TagName.of("Untagged")));
        fixture = PostCommandFixtures.forCommand(
                "assign-tag", config -> new AssignTagToPostCommand(FIXED_CLOCK, posts, tags));
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Test
    void raisesPostUpdatedWithTheTagAndSavesThePost() {
        PostId id = PostId.newId();

        fixture.given()
                .event(new PostPreCreatedEvent(id, "título", "conteúdo", UserFixtures.AUTHOR_ID, NOW))
                .when()
                .command(new AssignTagToPost(id, TAG_ID))
                .then()
                .success()
                .events(new PostUpdatedEvent(id, "título", "conteúdo", UserFixtures.AUTHOR_ID,
                        List.of(new PostUpdatedEvent.Tag("tag-1", "Untagged")), 2, NOW));

        assertThat(posts.findById(id)).hasValueSatisfying(post -> {
            assertThat(post.tags()).containsExactly(Tag.reference(TAG_ID, TagName.of("Untagged")));
            assertThat(post.version()).isEqualTo(new PostVersion(2));
        });
    }

    @Test
    void rejectsATagThePostAlreadyHas() {
        PostId id = PostId.newId();

        fixture.given()
                .event(new PostPreCreatedEvent(id, "título", "conteúdo", UserFixtures.AUTHOR_ID, NOW))
                .event(new PostUpdatedEvent(id, "título", "conteúdo", UserFixtures.AUTHOR_ID,
                        List.of(new PostUpdatedEvent.Tag("tag-1", "Untagged")), 2, NOW))
                .when()
                .command(new AssignTagToPost(id, TAG_ID))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, InvalidPostException.class)).isTrue());
    }

    @Test
    void rejectsATagThatDoesNotExist() {
        PostId id = PostId.newId();

        fixture.given()
                .event(new PostPreCreatedEvent(id, "título", "conteúdo", UserFixtures.AUTHOR_ID, NOW))
                .when()
                .command(new AssignTagToPost(id, TagId.of("nao-existe")))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, TagNotFoundException.class)).isTrue());
    }

    @Test
    void failsWithEntityNotFoundWhenThePostHasNoStream() {
        fixture.given()
                .noPriorActivity()
                .when()
                .command(new AssignTagToPost(PostId.newId(), TAG_ID))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, EntityNotFoundException.class)).isTrue());
    }
}
