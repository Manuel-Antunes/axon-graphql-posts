package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.application.post.command.CreatePostCommand.CreatePost;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.exception.PostAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.post.vo.Author;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import dev.manuelantunes.axonposts.domain.post.vo.PostVersion;
import dev.manuelantunes.axonposts.support.InMemoryPostRepository;
import dev.manuelantunes.axonposts.support.PostCommandFixtures;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.manuelantunes.axonposts.support.PostCommandFixtures.FIXED_CLOCK;
import static dev.manuelantunes.axonposts.support.PostCommandFixtures.NOW;
import static dev.manuelantunes.axonposts.support.PostCommandFixtures.hasCause;
import static org.assertj.core.api.Assertions.assertThat;

/** Given-when-then do {@link CreatePostCommand}, e só dele. */
class CreatePostCommandTest {

    private InMemoryPostRepository posts;
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        posts = new InMemoryPostRepository();
        fixture = PostCommandFixtures.forCommand(
                "create-post",
                config -> new CreatePostCommand(FIXED_CLOCK, posts)
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
                .command(new CreatePost(id, "  Axon 5 + GraphQL  ", "conteúdo", "manuel"))
                .then()
                .success()
                .resultMessagePayload(id)
                .events(new PostCreatedEvent(id, "Axon 5 + GraphQL", "conteúdo", "manuel", NOW));
    }

    @Test
    void savesThePostWithinTheCommand() {
        PostId id = PostId.newId();

        fixture.given()
                .noPriorActivity()
                .when()
                .command(new CreatePost(id, "  Axon 5 + GraphQL  ", "conteúdo", "  manuel  "))
                .then()
                .success();

        assertThat(posts.findById(id)).hasValueSatisfying(post -> {
            assertThat(post.title()).isEqualTo(PostTitle.of("Axon 5 + GraphQL"));
            assertThat(post.author()).isEqualTo(Author.of("manuel"));
            assertThat(post.version()).isEqualTo(PostVersion.initial());
            assertThat(post.tags()).isEmpty();
        });
    }

    @Test
    void rejectsABlankTitleWithoutSavingAnything() {
        fixture.given()
                .noPriorActivity()
                .when()
                .command(new CreatePost(PostId.newId(), "   ", "conteúdo", "manuel"))
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
                .command(new CreatePost(id, "outro", "conteúdo", "manuel"))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, PostAlreadyExistsException.class)).isTrue());

        assertThat(posts.all()).isEmpty();
    }
}
