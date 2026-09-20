package dev.manuelantunes.axonposts.e2e;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.application.user.command.LinkAccountCommand.LinkAccount;
import dev.manuelantunes.axonposts.application.user.command.PromoteToAuthorCommand.PromoteToAuthor;
import dev.manuelantunes.axonposts.application.user.command.RegisterUserCommand.RegisterUser;
import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import io.quarkus.test.junit.QuarkusTest;
import dev.manuelantunes.axonposts.support.GraphQl;
import dev.manuelantunes.axonposts.support.Realm;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class IdentityProvisioningE2ETest extends AbstractGraphQlE2ETest {
    @Inject
    UserRepository users;

    @Inject
    CommandGateway commandGateway;

    private UserId registerLocally(String email, String name, boolean author) {
        UserId id = UserId.newId();
        commandGateway.sendAndWait(new RegisterUser(id, email, name, author, null, null));
        return id;
    }

    @Test
    void theFirstRequestWithATokenCreatesTheLocalUser() {
        assertThat(users.findByEmail(Email.of(Realm.AUTHOR_USERNAME))).isEmpty();

        var response = asAuthor().execute("{ me { __typename id name email } }");

        assertThat(response.string("me.__typename")).isEqualTo("Author");
        assertThat(response.string("me.name")).isEqualTo("Manuel Antunes");
        assertThat(response.string("me.email")).isEqualTo(Realm.AUTHOR_USERNAME);

        assertThat(users.findByEmail(Email.of(Realm.AUTHOR_USERNAME)))
                .hasValueSatisfying(user -> {
                    assertThat(user.isAuthor()).isTrue();
                    assertThat(user.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue();
                    assertThat(user.accountFor(AuthProvider.KEYCLOAK).orElseThrow().subject())
                            .isNotEqualTo(user.id().value());
                });
    }

    @Test
    void provisioningIsIdempotent() {
        GraphQl author = asAuthor();

        String first = author.execute("{ me { id } }").string("me.id");
        String second = author.execute("{ me { id } }").string("me.id");

        assertThat(second).isEqualTo(first);
        assertThat(count("select count(*) from users")).isEqualTo(1);
        assertThat(count("select count(*) from accounts")).isEqualTo(1);
    }

    @Test
    void theAccountIsVisibleAndHasNoLocalPassword() {
        var response = asAuthor().execute("{ me { accounts { provider subject hasPassword } } }");

        assertThat(response.list("me.accounts")).hasSize(1);
        assertThat(response.string("me.accounts[0].provider")).isEqualTo("KEYCLOAK");
        assertThat(response.bool("me.accounts[0].hasPassword")).isFalse();
    }

    @Test
    void anExistingLocalUserIsLinkedInsteadOfDuplicated() {
        UserId existingId = registerLocally(Realm.READER_USERNAME, "Cadastro Antigo", false);

        assertThat(asReader().execute("{ me { id } }").string("me.id")).isEqualTo(existingId.value());

        assertThat(count("select count(*) from users")).isEqualTo(1);
        assertThat(users.findById(existingId))
                .hasValueSatisfying(user -> assertThat(user.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue());
    }

    @Test
    void linkingASecondProviderKeepsTheSameUserAndItsPosts() {
        GraphQl author = asAuthor();
        String userId = author.execute("{ me { id } }").string("me.id");
        author.createPost("Escrito antes do segundo provedor", "conteúdo");

        commandGateway.sendAndWait(new LinkAccount(UserId.of(userId), AuthProvider.GOOGLE, "google-sub-1"));

        var response = author.execute("""
                { me { id accounts { provider }
                       ... on Author { posts(first: 5) { edges { node { title } } } } } }""");

        assertThat(response.string("me.id")).isEqualTo(userId);
        assertThat(response.list("me.accounts")).hasSize(2);
        assertThat(response.list("me.posts.edges")).hasSize(1);
        assertThat(response.string("me.posts.edges[0].node.title"))
                .isEqualTo("Escrito antes do segundo provedor");

        assertThat(count("select count(*) from users")).isEqualTo(1);
    }

    @Test
    void aLocalReaderIsPromotedIntoANewAggregate() {
        UserId readerId = registerLocally(Realm.PROMOTED_USERNAME, "Autor Recente", false);
        assertThat(users.findById(readerId).orElseThrow().isAuthor()).isFalse();

        var response = as(Realm.PROMOTED_USERNAME).execute("{ me { __typename id } }");
        assertThat(response.string("me.__typename")).isEqualTo("Author");
        String authorId = response.string("me.id");

        assertThat(authorId).isNotEqualTo(readerId.value());

        assertThat(users.findById(UserId.of(authorId))).hasValueSatisfying(author -> {
            assertThat(author.isAuthor()).isTrue();
            assertThat(author.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue();
            assertThat(author.supersedes()).isEqualTo(readerId);
        });

        assertThat(users.findById(readerId)).hasValueSatisfying(reader -> {
            assertThat(reader.isSuperseded()).isTrue();
            assertThat(reader.supersededBy()).isEqualTo(UserId.of(authorId));
            assertThat(reader.accounts()).isEmpty();
        });

        assertThat(count("select count(*) from users")).isEqualTo(2);
        assertThat(users.findByEmail(Email.of(Realm.PROMOTED_USERNAME)))
                .hasValueSatisfying(user -> assertThat(user.id().value()).isEqualTo(authorId));
    }

    @Test
    void aPromotedUserCanWriteImmediately() {
        registerLocally(Realm.PROMOTED_USERNAME, "Autor Recente", false);

        as(Realm.PROMOTED_USERNAME).createPost("Primeiro depois da promoção", "c");

        assertThat(anonymous.execute("{ posts(first: 5) { edges { node { title author { name } } } } }")
                .list("posts.edges")).hasSize(1);
    }

    @Test
    void deletingTheAccountHidesItAndLoggingInAgainBringsItBack() {
        GraphQl author = asAuthor();
        String userId = author.execute("{ me { id } }").string("me.id");
        author.createPost("Escrito antes de apagar a conta", "conteúdo");

        assertThat(author.execute("mutation { deleteMe }").bool("deleteMe")).isTrue();

        assertThat(users.findById(UserId.of(userId))).isEmpty();
        assertThat(count("select count(*) from users where id = ? and deleted_at is not null", userId))
                .isEqualTo(1);

        assertThat(anonymous.execute("{ posts(first: 5) { edges { node { title } } } }")
                .list("posts.edges")).isEmpty();

        var back = asAuthor().execute(
                "{ me { id ... on Author { posts(first: 5) { edges { node { title } } } } } }");
        assertThat(back.string("me.id")).isEqualTo(userId);
        assertThat(back.list("me.posts.edges")).hasSize(1);

        assertThat(count("select count(*) from users")).isEqualTo(1);
    }

    @Test
    void deletingTheAccountAlsoHidesTheAuthorsPosts() {
        GraphQl author = asAuthor();
        author.createPost("Some com o autor", "conteúdo");
        assertThat(anonymous.execute("{ posts(first: 5) { edges { node { title } } } }")
                .list("posts.edges")).hasSize(1);

        author.execute("mutation { deleteMe }");

        assertThat(count("select count(*) from posts"))
                .as("a linha do post continua no banco: quem sumiu foi o autor").isEqualTo(1);
        assertThat(anonymous.execute("{ posts(first: 5) { edges { node { title } } } }")
                .list("posts.edges")).isEmpty();

        asAuthor().execute("{ me { id } }");
        assertThat(anonymous.execute("{ posts(first: 5) { edges { node { title } } } }")
                .list("posts.edges")).hasSize(1);
    }

    @Test
    void anInterruptedPromotionIsFinishedOnTheNextLogin() {
        UserId readerId = registerLocally(Realm.PROMOTED_USERNAME, "Autor Recente", false);
        UserId successorId = UserId.newId();

        commandGateway.sendAndWait(new PromoteToAuthor(readerId, successorId));

        assertThat(users.findById(readerId).orElseThrow().isSuperseded()).isTrue();
        assertThat(users.findById(successorId)).as("o sucessor não deveria existir ainda").isEmpty();

        var response = as(Realm.PROMOTED_USERNAME).execute("{ me { __typename id } }");
        assertThat(response.string("me.__typename")).isEqualTo("Author");
        assertThat(response.string("me.id")).isEqualTo(successorId.value());

        assertThat(users.findById(successorId)).hasValueSatisfying(author -> {
            assertThat(author.isAuthor()).isTrue();
            assertThat(author.supersedes()).isEqualTo(readerId);
            assertThat(author.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue();
        });

        assertThat(count("select count(*) from users")).isEqualTo(2);
    }

    @Test
    void anInvalidTokenIsRefusedAtTheHttpLayer() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer nao.e.um.jwt")
                .body("{\"query\":\"{ me { id } }\"}")
                .post("/graphql")
                .then().statusCode(401);
    }
}
