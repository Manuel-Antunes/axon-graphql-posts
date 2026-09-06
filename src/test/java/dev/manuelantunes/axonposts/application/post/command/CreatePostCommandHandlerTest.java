package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.application.post.PostView;
import dev.manuelantunes.axonposts.mapper.PostViewMapperImpl;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.exception.PostAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.support.InMemoryPostReadRepository;
import dev.manuelantunes.axonposts.support.PostCommandFixtures;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.manuelantunes.axonposts.support.PostCommandFixtures.FIXED_CLOCK;
import static dev.manuelantunes.axonposts.support.PostCommandFixtures.NOW;
import static dev.manuelantunes.axonposts.support.PostCommandFixtures.hasCause;
import static org.assertj.core.api.Assertions.assertThat;

/** Given-when-then do {@link CreatePostCommandHandler}, e só dele. */
class CreatePostCommandHandlerTest {

    private InMemoryPostReadRepository posts;
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        posts = new InMemoryPostReadRepository();
        fixture = PostCommandFixtures.forHandler(
                "create-post",
                config -> new CreatePostCommandHandler(FIXED_CLOCK, posts, new PostViewMapperImpl())
        );
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Test
    void appendsPostCreatedAndReturnsTheId() {
        PostId id = PostId.newId();

        fixture.given()
                .noPriorActivity()
                .when()
                .command(new CreatePostCommand(id, "  Axon 5 + GraphQL  ", "conteúdo", "manuel"))
                .then()
                .success()
                .resultMessagePayload(id)
                .events(new PostCreatedEvent(id, "Axon 5 + GraphQL", "conteúdo", "manuel", NOW));
    }

    @Test
    void savesTheReadModelWithinTheCommand() {
        PostId id = PostId.newId();

        fixture.given()
                .noPriorActivity()
                .when()
                .command(new CreatePostCommand(id, "  Axon 5 + GraphQL  ", "conteúdo", "  manuel  "))
                .then()
                .success();

        assertThat(posts.findById(id.value()))
                .contains(new PostView(id.value(), "Axon 5 + GraphQL", "conteúdo", "manuel", NOW, NOW, 1));
    }

    @Test
    void rejectsABlankTitleWithoutSavingAnything() {
        fixture.given()
                .noPriorActivity()
                .when()
                .command(new CreatePostCommand(PostId.newId(), "   ", "conteúdo", "manuel"))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, InvalidPostException.class)).isTrue());

        assertThat(posts.all()).isEmpty();
    }

    @Test
    void rejectsAnIdThatAlreadyHasAStream() {
        PostId id = PostId.newId();

        fixture.given()
                .event(new PostCreatedEvent(id, "título", "conteúdo", "manuel", NOW))
                .when()
                .command(new CreatePostCommand(id, "outro", "conteúdo", "manuel"))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, PostAlreadyExistsException.class)).isTrue());

        assertThat(posts.all()).isEmpty();
    }
}
