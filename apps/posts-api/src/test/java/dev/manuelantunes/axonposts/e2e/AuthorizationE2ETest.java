package dev.manuelantunes.axonposts.e2e;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import io.quarkus.test.junit.QuarkusTest;
import dev.manuelantunes.axonposts.support.GraphQl;
import dev.manuelantunes.axonposts.support.Realm;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class AuthorizationE2ETest extends AbstractGraphQlE2ETest {
    @Inject
    UserRepository users;

    static Stream<Arguments> writeMutations() {
        return Stream.of(
                Arguments.of("createPost",
                        "mutation { createPost(input: {title: \"x\", content: \"y\"}) { id } }"),
                Arguments.of("updatePost",
                        "mutation { updatePost(input: {id: \"nao-existe\", title: \"x\"}) { id } }"),
                Arguments.of("deletePost",
                        "mutation { deletePost(id: \"nao-existe\") }"),
                Arguments.of("restorePost",
                        "mutation { restorePost(id: \"nao-existe\") { id } }"));
    }

    @ParameterizedTest(name = "{0} sem token")
    @MethodSource("writeMutations")
    void anonymousIsRefused(String name, String document) {
        assertThat(anonymous.attempt(document).errorCode()).isEqualTo("UNAUTHORIZED");
    }

    @ParameterizedTest(name = "{0} como leitor")
    @MethodSource("writeMutations")
    void aReaderIsRefused(String name, String document) {
        assertThat(asReader().attempt(document).errorCode()).isEqualTo("FORBIDDEN");
    }

    @Test
    void aDeniedRequestProvisionsNothing() {
        asReader().attempt("mutation { createPost(input: {title: \"x\", content: \"y\"}) { id } }")
                .errorCode();

        assertThat(users.findByEmail(Email.of(Realm.READER_USERNAME))).isEmpty();
        assertThat(count("select count(*) from users")).isZero();
    }

    @Test
    void aRefusalSaysSomethingInsteadOfPublishingANullMessage() {
        assertThat(anonymous.attempt("{ me { id } }").errorMessage())
                .isEqualTo("credenciais inválidas ou ausentes");

        assertThat(asReader()
                .attempt("mutation { createPost(input: {title: \"x\", content: \"y\"}) { id } }")
                .errorMessage()).isEqualTo("sem permissão para esta operação");
    }

    @Test
    void readingIsOpenToAnyone() {
        asAuthor().createPost("Público", "qualquer um lê");

        var response = anonymous.execute("{ posts(first: 5) { edges { node { title author { name } } } } }");
        assertThat(response.list("posts.edges")).hasSize(1);
        assertThat(response.string("posts.edges[0].node.author.name")).isEqualTo("Manuel Antunes");
    }

    @Test
    void meRequiresATokenButAnyRoleServes() {
        assertThat(anonymous.attempt("{ me { id } }").errorCode()).isEqualTo("UNAUTHORIZED");
        assertThat(asReader().execute("{ me { __typename email } }").string("me.__typename")).isEqualTo("Reader");
    }

    @Test
    void theRealmRoleDecidesTheLocalType() {
        assertThat(asAuthor().execute("{ me { __typename } }").string("me.__typename")).isEqualTo("Author");
        assertThat(asReader().execute("{ me { __typename } }").string("me.__typename")).isEqualTo("Reader");

        assertThat(users.findByEmail(Email.of(Realm.AUTHOR_USERNAME)))
                .hasValueSatisfying(user -> assertThat(user.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue());
    }

    @Test
    void anAuthorCannotTouchAnotherAuthorsPost() {
        String alheio = asAuthor().createPost("Do Manuel", "conteúdo");
        GraphQl outro = as(Realm.PROMOTED_USERNAME);

        assertThat(outro.attempt("mutation E($id: ID!) { updatePost(input: {id: $id, title: \"x\"}) { id } }",
                "id", alheio).errorCode()).isEqualTo("FORBIDDEN");
        assertThat(outro.attempt("mutation D($id: ID!) { deletePost(id: $id) }", "id", alheio).errorCode())
                .isEqualTo("FORBIDDEN");

        assertThat(anonymous.execute("query P($id: ID!) { post(id: $id) { title } }", "id", alheio)
                .string("post.title")).isEqualTo("Do Manuel");
    }

    @Test
    void theOwnerCanDoAllThreeOnTheirOwnPost() {
        GraphQl author = asAuthor();
        String meu = author.createPost("Meu", "conteúdo");

        assertThat(author.execute(
                "mutation E($id: ID!) { updatePost(input: {id: $id, title: \"Meu, editado\"}) { title } }",
                "id", meu).string("updatePost.title")).isEqualTo("Meu, editado");
        assertThat(author.execute("mutation D($id: ID!) { deletePost(id: $id) }", "id", meu)
                .bool("deletePost")).isTrue();
        assertThat(author.execute("mutation R($id: ID!) { restorePost(id: $id) { title } }", "id", meu)
                .string("restorePost.title")).isEqualTo("Meu, editado");
    }

    @Test
    void ownershipHoldsEvenWhenThePostIsHiddenBySoftDelete() {
        GraphQl author = asAuthor();
        String meu = author.createPost("Some e volta", "conteúdo");
        author.execute("mutation D($id: ID!) { deletePost(id: $id) }", "id", meu);

        assertThat(as(Realm.PROMOTED_USERNAME)
                .attempt("mutation R($id: ID!) { restorePost(id: $id) { id } }", "id", meu)
                .errorCode()).isEqualTo("FORBIDDEN");
    }
}
