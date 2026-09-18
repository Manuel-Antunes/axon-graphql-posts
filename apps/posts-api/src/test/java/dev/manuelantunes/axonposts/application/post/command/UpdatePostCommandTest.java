package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.application.post.command.UpdatePostCommand.UpdatePost;
import dev.manuelantunes.axonposts.domain.post.event.PostPreCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.vo.PostContent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import dev.manuelantunes.axonposts.domain.post.vo.PostVersion;
import dev.manuelantunes.axonposts.support.InMemoryPostRepository;
import dev.manuelantunes.axonposts.support.UserFixtures;
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

/** Given-when-then do {@link UpdatePostCommand}, e só dele. */
class UpdatePostCommandTest {

    private InMemoryPostRepository posts;
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        posts = new InMemoryPostRepository();
        fixture = PostCommandFixtures.forCommand(
                "update-post",
                config -> new UpdatePostCommand(FIXED_CLOCK, posts)
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
                .event(new PostPreCreatedEvent(id, "título", "conteúdo", UserFixtures.AUTHOR_ID, NOW))
                .when()
                .command(new UpdatePost(id, "novo título", null, UserFixtures.AUTHOR_ID))
                .then()
                .success()
                .events(new PostUpdatedEvent(id, "novo título", "conteúdo", UserFixtures.AUTHOR_ID, List.of(), 2, NOW));
    }

    @Test
    void savesThePostWithTheVersionBumped() {
        PostId id = PostId.newId();

        fixture.given()
                .event(new PostPreCreatedEvent(id, "título", "conteúdo", UserFixtures.AUTHOR_ID, NOW))
                .event(new PostUpdatedEvent(id, "título v2", "conteúdo", UserFixtures.AUTHOR_ID, List.of(), 2, NOW))
                .when()
                .command(new UpdatePost(id, null, "conteúdo v3", UserFixtures.AUTHOR_ID))
                .then()
                .success();

        // v1 criação + v2 update anterior + este = 3, com autor e createdAt vindos do stream
        assertThat(posts.findById(id)).hasValueSatisfying(post -> {
            assertThat(post.title()).isEqualTo(PostTitle.of("título v2"));
            assertThat(post.content()).isEqualTo(PostContent.of("conteúdo v3"));
            assertThat(post.author()).isEqualTo(UserFixtures.author());
            assertThat(post.createdAt()).isEqualTo(NOW);
            assertThat(post.version()).isEqualTo(new PostVersion(3));
        });
    }

    @Test
    void reflectsPreviousUpdates() {
        PostId id = PostId.newId();

        fixture.given()
                .event(new PostPreCreatedEvent(id, "título", "conteúdo", UserFixtures.AUTHOR_ID, NOW))
                .event(new PostUpdatedEvent(id, "título v2", "conteúdo", UserFixtures.AUTHOR_ID, List.of(), 2, NOW))
                .when()
                .command(new UpdatePost(id, null, "conteúdo v3", UserFixtures.AUTHOR_ID))
                .then()
                .success()
                .events(new PostUpdatedEvent(id, "título v2", "conteúdo v3", UserFixtures.AUTHOR_ID, List.of(), 3, NOW));
    }

    @Test
    void rejectsAnUpdateWithoutChangesAndSavesNothing() {
        PostId id = PostId.newId();

        fixture.given()
                .event(new PostPreCreatedEvent(id, "título", "conteúdo", UserFixtures.AUTHOR_ID, NOW))
                .when()
                .command(new UpdatePost(id, "título", null, UserFixtures.AUTHOR_ID))
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
                .command(new UpdatePost(PostId.newId(), "x", null, UserFixtures.AUTHOR_ID))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, EntityNotFoundException.class)).isTrue());

        assertThat(posts.all()).isEmpty();
    }
}
