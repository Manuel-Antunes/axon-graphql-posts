package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.application.post.PostView;
import dev.manuelantunes.axonposts.mapper.PostViewMapperImpl;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.support.InMemoryPostReadRepository;
import dev.manuelantunes.axonposts.support.PostCommandFixtures;
import org.axonframework.modelling.repository.EntityNotFoundException;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.manuelantunes.axonposts.support.PostCommandFixtures.FIXED_CLOCK;
import static dev.manuelantunes.axonposts.support.PostCommandFixtures.NOW;
import static dev.manuelantunes.axonposts.support.PostCommandFixtures.hasCause;
import static org.assertj.core.api.Assertions.assertThat;

/** Given-when-then do {@link UpdatePostCommandHandler}, e só dele. */
class UpdatePostCommandHandlerTest {

    private InMemoryPostReadRepository posts;
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        posts = new InMemoryPostReadRepository();
        fixture = PostCommandFixtures.forHandler(
                "update-post",
                config -> new UpdatePostCommandHandler(FIXED_CLOCK, posts, new PostViewMapperImpl())
        );
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Test
    void keepsUntouchedFieldsAndAppendsTheResultingState() {
        PostId id = PostId.newId();

        fixture.given()
                .event(new PostCreatedEvent(id, "título", "conteúdo", "manuel", NOW))
                .when()
                .command(new UpdatePostCommand(id, "novo título", null))
                .then()
                .success()
                .events(new PostUpdatedEvent(id, "novo título", "conteúdo", NOW));
    }

    @Test
    void savesTheReadModelWithTheVersionBumped() {
        PostId id = PostId.newId();

        fixture.given()
                .event(new PostCreatedEvent(id, "título", "conteúdo", "manuel", NOW))
                .event(new PostUpdatedEvent(id, "título v2", "conteúdo", NOW))
                .when()
                .command(new UpdatePostCommand(id, null, "conteúdo v3"))
                .then()
                .success();

        // v1 criação + v2 update anterior + este = 3, com autor e createdAt vindos do stream
        assertThat(posts.findById(id.value()))
                .contains(new PostView(id.value(), "título v2", "conteúdo v3", "manuel", NOW, NOW, 3));
    }

    @Test
    void reflectsPreviousUpdates() {
        PostId id = PostId.newId();

        fixture.given()
                .event(new PostCreatedEvent(id, "título", "conteúdo", "manuel", NOW))
                .event(new PostUpdatedEvent(id, "título v2", "conteúdo", NOW))
                .when()
                .command(new UpdatePostCommand(id, null, "conteúdo v3"))
                .then()
                .success()
                .events(new PostUpdatedEvent(id, "título v2", "conteúdo v3", NOW));
    }

    @Test
    void rejectsAnUpdateWithoutChangesAndSavesNothing() {
        PostId id = PostId.newId();

        fixture.given()
                .event(new PostCreatedEvent(id, "título", "conteúdo", "manuel", NOW))
                .when()
                .command(new UpdatePostCommand(id, "título", null))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, InvalidPostException.class)).isTrue());

        assertThat(posts.all()).isEmpty();
    }

    @Test
    void failsWithEntityNotFoundWhenThePostHasNoStream() {
        fixture.given()
                .noPriorActivity()
                .when()
                .command(new UpdatePostCommand(PostId.newId(), "x", null))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, EntityNotFoundException.class)).isTrue());

        assertThat(posts.all()).isEmpty();
    }
}
