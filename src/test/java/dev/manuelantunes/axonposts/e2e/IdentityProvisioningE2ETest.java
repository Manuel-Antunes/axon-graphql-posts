package dev.manuelantunes.axonposts.e2e;

import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import dev.manuelantunes.axonposts.support.KeycloakContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A costura entre o Keycloak e o banco local: provisionamento just-in-time e account linking.
 *
 * <h2>O que só existe integrado</h2>
 * Cada afirmação aqui depende de um token assinado pelo realm de verdade:
 * <ul>
 *   <li>o {@code issuer-uri} descobrir o JWKS e o decoder validar a assinatura;</li>
 *   <li>as roles saírem de {@code realm_access.roles} — um erro de prefixo no conversor faria todo
 *       {@code hasRole} falhar em silêncio, e um token forjado não pegaria isso;</li>
 *   <li>o {@code sub} do Keycloak virar linha em {@code accounts}, ligada a um {@code User} local com id
 *       próprio;</li>
 *   <li>a role {@code author} virar linha em {@code authors}.</li>
 * </ul>
 */
class IdentityProvisioningE2ETest extends AbstractGraphQlE2ETest {

    @Autowired
    private UserRepository users;

    @Autowired
    private Clock clock;

    @Test
    void theFirstRequestWithATokenCreatesTheLocalUser() {
        assertThat(users.findByEmail(Email.of(KeycloakContainerConfig.AUTHOR_USERNAME))).isEmpty();

        asAuthor().document("{ me { __typename id name email } }")
                .execute()
                .path("me.__typename").entity(String.class).isEqualTo("Author")
                .path("me.name").entity(String.class).isEqualTo("Manuel Antunes")
                .path("me.email").entity(String.class).isEqualTo(KeycloakContainerConfig.AUTHOR_USERNAME);

        assertThat(users.findByEmail(Email.of(KeycloakContainerConfig.AUTHOR_USERNAME)))
                .hasValueSatisfying(user -> {
                    assertThat(user.isAuthor()).isTrue();
                    assertThat(user.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue();
                    // dois espaços de identidade: o sub do Keycloak NÃO é a chave primária local
                    assertThat(user.accountFor(AuthProvider.KEYCLOAK).orElseThrow().subject())
                            .isNotEqualTo(user.id().value());
                });
    }

    @Test
    void provisioningIsIdempotent() {
        HttpGraphQlTester author = asAuthor();

        String first = author.document("{ me { id } }").execute().path("me.id").entity(String.class).get();
        String second = author.document("{ me { id } }").execute().path("me.id").entity(String.class).get();

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from accounts", Integer.class)).isEqualTo(1);
    }

    @Test
    void theAccountIsVisibleAndHasNoLocalPassword() {
        asAuthor().document("{ me { accounts { provider subject hasPassword } } }")
                .execute()
                .path("me.accounts").entityList(Object.class).hasSize(1)
                .path("me.accounts[0].provider").entity(String.class).isEqualTo("KEYCLOAK")
                // o "algumas contas têm senha e outras não", ponta a ponta: esta não tem
                .path("me.accounts[0].hasPassword").entity(Boolean.class).isEqualTo(false);
    }

    @Test
    void anExistingLocalUserIsLinkedInsteadOfDuplicated() {
        // já existe alguém local com este e-mail, sem conta nenhuma ligada
        UserId existingId = UserId.newId();
        users.save(User.register(existingId, KeycloakContainerConfig.READER_USERNAME,
                "Cadastro Antigo", clock.instant()));

        asReader().document("{ me { id } }").execute()
                .path("me.id").entity(String.class).isEqualTo(existingId.value());

        // account linking: a mesma pessoa, uma conta a mais — e não um segundo usuário
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
        assertThat(users.findById(existingId))
                .hasValueSatisfying(user -> assertThat(user.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue());
    }

    @Test
    void aLinkedUserKeepsThePostsItAlreadyHad() {
        // o autor entra uma vez, escreve, e some do banco local só a conta — simulando um relink
        HttpGraphQlTester author = asAuthor();
        String userId = author.document("{ me { id } }").execute().path("me.id").entity(String.class).get();
        createPost(author, "Escrito antes do relink", "conteúdo");

        jdbc.update("delete from accounts where user_id = ?", userId);

        // ao voltar, é ligado ao mesmo usuário pelo e-mail — e os posts continuam sendo dele
        author.document("{ me { id ... on Author { posts(first: 5) { edges { node { title } } } } } }")
                .execute()
                .path("me.id").entity(String.class).isEqualTo(userId)
                .path("me.posts.edges").entityList(Object.class).hasSize(1)
                .path("me.posts.edges[0].node.title").entity(String.class).isEqualTo("Escrito antes do relink");
    }

    @Test
    void aLocalReaderIsPromotedWhenTheTokenBringsTheAuthorRole() {
        // o caso que a herança JOINED torna trabalhoso: a linha já existe como Reader...
        UserId existingId = UserId.newId();
        users.save(User.register(existingId, KeycloakContainerConfig.PROMOTED_USERNAME,
                "Autor Recente", Instant.now()));
        assertThat(users.findById(existingId).orElseThrow().isAuthor()).isFalse();

        // ...e o token traz a role author. Promover é inserir a linha filha, não recriar o usuário
        as(KeycloakContainerConfig.PROMOTED_USERNAME)
                .document("{ me { __typename id } }")
                .execute()
                .path("me.__typename").entity(String.class).isEqualTo("Author")
                .path("me.id").entity(String.class).isEqualTo(existingId.value());

        assertThat(users.findById(existingId)).hasValueSatisfying(user -> {
            assertThat(user.isAuthor()).isTrue();
            assertThat(user.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue();
        });
        // um usuário só: promover não duplicou nada
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
    }

    @Test
    void aPromotedUserCanWriteImmediately() {
        users.save(User.register(UserId.newId(), KeycloakContainerConfig.PROMOTED_USERNAME,
                "Autor Recente", Instant.now()));

        // a promoção precisa valer já nesta requisição: o CurrentUser relê para pegar o tipo novo
        createPost(as(KeycloakContainerConfig.PROMOTED_USERNAME), "Primeiro depois da promoção", "c");

        anonymous.document("{ posts(first: 5) { edges { node { title author { name } } } } }")
                .execute()
                .path("posts.edges").entityList(Object.class).hasSize(1);
    }

    /**
     * Um token malformado nem chega ao GraphQL: o filtro do resource server recusa a requisição em HTTP,
     * com {@code 401} e {@code WWW-Authenticate}, antes de existir um documento a executar.
     * <p>
     * É por isso que a asserção é sobre o status e não sobre um erro GraphQL — e é uma diferença que vale
     * conhecer: um token <b>ausente</b> segue anônimo e esbarra no {@code @PreAuthorize} (erro GraphQL),
     * um token <b>inválido</b> morre antes (erro HTTP).
     */
    @Test
    void anInvalidTokenIsRefusedAtTheHttpLayer() {
        webTestClient.post()
                .uri("/graphql")
                .header("Authorization", "Bearer nao.e.um.jwt")
                .header("Content-Type", "application/json")
                .bodyValue("{\"query\":\"{ me { id } }\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
