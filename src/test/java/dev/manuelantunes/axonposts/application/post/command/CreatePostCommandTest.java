package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.application.post.command.CreatePostCommand.CreatePost;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.exception.PostAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import dev.manuelantunes.axonposts.domain.post.vo.PostVersion;
import dev.manuelantunes.axonposts.domain.user.exception.NotAnAuthorException;
import dev.manuelantunes.axonposts.support.InMemoryPostRepository;
import dev.manuelantunes.axonposts.support.InMemoryUserRepository;
import dev.manuelantunes.axonposts.support.UserFixtures;
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
    private InMemoryUserRepository users;
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        posts = new InMemoryPostRepository();
        // o autor e um leitor: o command tem de aceitar um e recusar o outro
        users = new InMemoryUserRepository(UserFixtures.author(), UserFixtures.reader());
        fixture = PostCommandFixtures.forCommand(
                "create-post",
                config -> new CreatePostCommand(FIXED_CLOCK, posts, users)
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
                .command(new CreatePost(id, "  Axon 5 + GraphQL  ", "conteúdo", UserFixtures.AUTHOR_ID))
                .then()
                .success()
                .resultMessagePayload(id)
                .events(new PostCreatedEvent(id, "Axon 5 + GraphQL", "conteúdo",
                        UserFixtures.AUTHOR_ID, UserFixtures.AUTHOR_NAME, NOW));
    }

    @Test
    void savesThePostWithinTheCommand() {
        PostId id = PostId.newId();

        fixture.given()
                .noPriorActivity()
                .when()
                .command(new CreatePost(id, "  Axon 5 + GraphQL  ", "conteúdo", UserFixtures.AUTHOR_ID))
                .then()
                .success();

        assertThat(posts.findById(id)).hasValueSatisfying(post -> {
            assertThat(post.title()).isEqualTo(PostTitle.of("Axon 5 + GraphQL"));
            assertThat(post.author()).isEqualTo(UserFixtures.author());
            assertThat(post.version()).isEqualTo(PostVersion.initial());
            assertThat(post.tags()).isEmpty();
        });
    }

    @Test
    void rejectsAUserThatIsNotAnAuthor() {
        // o instanceof do handler é a última palavra: o leitor existe, mas não é Author
        fixture.given()
                .noPriorActivity()
                .when()
                .command(new CreatePost(PostId.newId(), "título", "conteúdo", UserFixtures.READER_ID))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, NotAnAuthorException.class)).isTrue());

        assertThat(posts.all()).isEmpty();
    }

    @Test
    void rejectsABlankTitleWithoutSavingAnything() {
        fixture.given()
                .noPriorActivity()
                .when()
                .command(new CreatePost(PostId.newId(), "   ", "conteúdo", UserFixtures.AUTHOR_ID))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, InvalidPostException.class)).isTrue());

        assertThat(posts.all()).isEmpty();
    }

    @Test
    void rejectsAnIdThatAlreadyHasAStream() {
        PostId id = PostId.newId();

        fixture.given()
                .event(new PostCreatedEvent(id, "título", "conteúdo",
                        UserFixtures.AUTHOR_ID, UserFixtures.AUTHOR_NAME, NOW))
                .when()
                .command(new CreatePost(id, "outro", "conteúdo", UserFixtures.AUTHOR_ID))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, PostAlreadyExistsException.class)).isTrue());

        assertThat(posts.all()).isEmpty();
    }
}
