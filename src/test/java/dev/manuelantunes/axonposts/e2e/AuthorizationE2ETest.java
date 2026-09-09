package dev.manuelantunes.axonposts.e2e;

import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import dev.manuelantunes.axonposts.support.KeycloakContainerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A matriz de autorização: quem pode o quê, em cada mutation que exige papel.
 *
 * <h2>Por que uma tabela e não um teste por caso</h2>
 * As quatro mutations de escrita têm exatamente a mesma regra ({@code hasRole('AUTHOR')}), e o risco real
 * é uma delas <b>esquecer</b> a anotação. Um teste por mutation esconderia isso — o que falta é o caso que
 * ninguém escreveu. Enumerando as operações numa fonte de argumentos, acrescentar uma mutation sem
 * acrescentar a linha aqui fica visível na revisão.
 */
class AuthorizationE2ETest extends AbstractGraphQlE2ETest {

    @Autowired
    private UserRepository users;

    /** As mutations que exigem ROLE_AUTHOR, cada uma com um documento que não depende de estado. */
    static final class WriteMutations implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
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
    }

    @ParameterizedTest(name = "{0} sem token")
    @ArgumentsSource(WriteMutations.class)
    void anonymousIsRefused(String name, String document) {
        expectForbidden(anonymous.document(document).execute());
    }

    @ParameterizedTest(name = "{0} como leitor")
    @ArgumentsSource(WriteMutations.class)
    void aReaderIsRefused(String name, String document) {
        expectForbidden(asReader().document(document).execute());
    }

    @Test
    void aDeniedRequestProvisionsNothing() {
        asReader().document("mutation { createPost(input: {title: \"x\", content: \"y\"}) { id } }")
                .execute()
                .errors().expect(error -> true).verify();

        // o @PreAuthorize barra antes de o CurrentUser rodar: uma recusa não deixa linha no banco
        assertThat(users.findByEmail(Email.of(KeycloakContainerConfig.READER_USERNAME))).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isZero();
    }

    @Test
    void readingIsOpenToAnyone() {
        createPost(asAuthor(), "Público", "qualquer um lê");

        // queries não exigem token: é o que permite a home do blog funcionar deslogado
        anonymous.document("{ posts(first: 5) { edges { node { title author { name } } } } }")
                .execute()
                .path("posts.edges").entityList(Object.class).hasSize(1)
                .path("posts.edges[0].node.author.name").entity(String.class).isEqualTo("Manuel Antunes");
    }

    @Test
    void meRequiresATokenButAnyRoleServes() {
        anonymous.document("{ me { id } }").execute()
                .errors().expect(error -> error.getExtensions().get("classification") != null).verify();

        asReader().document("{ me { __typename email } }").execute()
                .path("me.__typename").entity(String.class).isEqualTo("Reader");
    }

    @Test
    void theRealmRoleDecidesTheLocalType() {
        asAuthor().document("{ me { __typename } }").execute()
                .path("me.__typename").entity(String.class).isEqualTo("Author");
        asReader().document("{ me { __typename } }").execute()
                .path("me.__typename").entity(String.class).isEqualTo("Reader");

        // e as duas contas ficaram ligadas ao provedor certo
        assertThat(users.findByEmail(Email.of(KeycloakContainerConfig.AUTHOR_USERNAME)))
                .hasValueSatisfying(user -> assertThat(user.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue());
    }

    private void expectForbidden(GraphQlTester.Response response) {
        response.errors()
                .expect(error -> "FORBIDDEN".equals(String.valueOf(error.getExtensions().get("classification"))))
                .verify();
    }
}
