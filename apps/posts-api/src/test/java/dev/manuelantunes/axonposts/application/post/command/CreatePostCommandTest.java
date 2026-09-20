package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.application.post.command.CreatePostCommand.CreatePost;
import dev.manuelantunes.axonposts.domain.post.event.PostPreCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.exception.PostAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import dev.manuelantunes.axonposts.domain.post.vo.PostVersion;
import dev.manuelantunes.axonposts.support.InMemoryPostRepository;
import dev.manuelantunes.axonposts.support.InMemoryTagRepository;
import dev.manuelantunes.axonposts.testing.UserFixtures;
import dev.manuelantunes.axonposts.support.PostCommandFixtures;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.manuelantunes.axonposts.support.PostCommandFixtures.FIXED_CLOCK;
import static dev.manuelantunes.axonposts.support.PostCommandFixtures.NOW;
import static dev.manuelantunes.axonposts.support.PostCommandFixtures.hasCause;
import static org.assertj.core.api.Assertions.assertThat;

/** Given-when-then do {@link CreatePostCommand}, e só dele. */
class CreatePostCommandTest {

    private InMemoryPostRepository posts;
    private InMemoryTagRepository tags;
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        posts = new InMemoryPostRepository();
        tags = new InMemoryTagRepository();
        fixture = PostCommandFixtures.forCommand(
                "create-post",
                config -> new CreatePostCommand(FIXED_CLOCK, posts, tags)
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
                .command(new CreatePost(id, "  Axon 5 + GraphQL  ", "conteúdo", UserFixtures.AUTHOR_ID, List.of()))
                .then()
                .success()
                .resultMessagePayload(id)
                .events(new PostPreCreatedEvent(id, "Axon 5 + GraphQL", "conteúdo",
                        UserFixtures.AUTHOR_ID, NOW));
    }

    @Test
    void savesThePostWithinTheCommand() {
        PostId id = PostId.newId();

        fixture.given()
                .noPriorActivity()
                .when()
                .command(new CreatePost(id, "  Axon 5 + GraphQL  ", "conteúdo", UserFixtures.AUTHOR_ID, List.of()))
                .then()
                .success();

        assertThat(posts.findById(id)).hasValueSatisfying(post -> {
            assertThat(post.title()).isEqualTo(PostTitle.of("Axon 5 + GraphQL"));
            assertThat(post.author().id()).isEqualTo(UserFixtures.AUTHOR_ID);
            assertThat(post.version()).isEqualTo(PostVersion.initial());
            assertThat(post.tags()).isEmpty();
        });
    }

    @Test
    void rejectsABlankTitleWithoutSavingAnything() {
        fixture.given()
                .noPriorActivity()
                .when()
                .command(new CreatePost(PostId.newId(), "   ", "conteúdo", UserFixtures.AUTHOR_ID, List.of()))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, InvalidPostException.class)).isTrue());

        assertThat(posts.all()).isEmpty();
    }

    @Test
    void rejectsAnIdThatAlreadyHasAStream() {
        PostId id = PostId.newId();

        fixture.given()
                .event(new PostPreCreatedEvent(id, "título", "conteúdo",
                        UserFixtures.AUTHOR_ID, NOW))
                .when()
                .command(new CreatePost(id, "outro", "conteúdo", UserFixtures.AUTHOR_ID, List.of()))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, PostAlreadyExistsException.class)).isTrue());

        assertThat(posts.all()).isEmpty();
    }
}
