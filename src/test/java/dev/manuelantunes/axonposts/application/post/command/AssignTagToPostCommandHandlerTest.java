package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.post.vo.PostVersion;
import dev.manuelantunes.axonposts.domain.post.vo.TagRef;
import dev.manuelantunes.axonposts.support.InMemoryPostRepository;
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

/** Given-when-then do {@link AssignTagToPostCommandHandler}, e só dele. */
class AssignTagToPostCommandHandlerTest {

    private InMemoryPostRepository posts;
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        posts = new InMemoryPostRepository();
        fixture = PostCommandFixtures.forHandler(
                "assign-tag", config -> new AssignTagToPostCommandHandler(FIXED_CLOCK, posts));
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Test
    void raisesPostUpdatedWithTheTagAndSavesThePost() {
        PostId id = PostId.newId();

        fixture.given()
                .event(new PostCreatedEvent(id, "título", "conteúdo", "manuel", NOW))
                .when()
                .command(new AssignTagToPostCommand(id, "tag-1", "Untagged"))
                .then()
                .success()
                .events(new PostUpdatedEvent(id, "título", "conteúdo",
                        List.of(new PostUpdatedEvent.Tag("tag-1", "Untagged")), 2, NOW));

        assertThat(posts.findById(id)).hasValueSatisfying(post -> {
            assertThat(post.tags()).containsExactly(TagRef.of("tag-1", "Untagged"));
            assertThat(post.version()).isEqualTo(new PostVersion(2));
        });
    }

    @Test
    void rejectsATagThePostAlreadyHas() {
        PostId id = PostId.newId();

        fixture.given()
                .event(new PostCreatedEvent(id, "título", "conteúdo", "manuel", NOW))
                .event(new PostUpdatedEvent(id, "título", "conteúdo",
                        List.of(new PostUpdatedEvent.Tag("tag-1", "Untagged")), 2, NOW))
                .when()
                .command(new AssignTagToPostCommand(id, "tag-1", "Untagged"))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, InvalidPostException.class)).isTrue());
    }

    @Test
    void failsWithEntityNotFoundWhenThePostHasNoStream() {
        fixture.given()
                .noPriorActivity()
                .when()
                .command(new AssignTagToPostCommand(PostId.newId(), "tag-1", "Untagged"))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, EntityNotFoundException.class)).isTrue());
    }
}
