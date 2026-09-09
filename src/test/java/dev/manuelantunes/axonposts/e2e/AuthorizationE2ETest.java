package dev.manuelantunes.axonposts.e2e;

import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import dev.manuelantunes.axonposts.support.KeycloakContainerConfig;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
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

    /**
     * A regra que faltava: ser autor autoriza a escrever, não a escrever no alheio.
     * <p>
     * A checagem está no <b>domínio</b> ({@code Post.assertWrittenBy}), e não no controller, porque
     * depende do estado do agregado — invariante, não permissão. Por isso vale venha o command de onde
     * vier, e por isso este teste percorre as três mutations.
     */
    @Test
    void anAuthorCannotTouchAnotherAuthorsPost() {
        String alheio = createPost(asAuthor(), "Do Manuel", "conteúdo");
        HttpGraphQlTester outro = as(KeycloakContainerConfig.PROMOTED_USERNAME);

        expectForbidden(outro.document("mutation E($id: ID!) { updatePost(input: {id: $id, title: \"x\"}) { id } }")
                .variable("id", alheio).execute());
        expectForbidden(outro.document("mutation D($id: ID!) { deletePost(id: $id) }")
                .variable("id", alheio).execute());

        // e o post continua intacto
        anonymous.document("query P($id: ID!) { post(id: $id) { title } }")
                .variable("id", alheio).execute()
                .path("post.title").entity(String.class).isEqualTo("Do Manuel");
    }

    @Test
    void theOwnerCanDoAllThreeOnTheirOwnPost() {
        HttpGraphQlTester author = asAuthor();
        String meu = createPost(author, "Meu", "conteúdo");

        author.document("mutation E($id: ID!) { updatePost(input: {id: $id, title: \"Meu, editado\"}) { title } }")
                .variable("id", meu).execute()
                .path("updatePost.title").entity(String.class).isEqualTo("Meu, editado");
        author.document("mutation D($id: ID!) { deletePost(id: $id) }").variable("id", meu).execute()
                .path("deletePost").entity(Boolean.class).isEqualTo(true);
        author.document("mutation R($id: ID!) { restorePost(id: $id) { title } }").variable("id", meu).execute()
                .path("restorePost.title").entity(String.class).isEqualTo("Meu, editado");
    }

    /**
     * O caso mais delicado: um post apagado é invisível para o JPA, e ainda assim a checagem de dono
     * funciona — o agregado vem do stream, com o {@code author} reconstituído do {@code PostCreatedEvent}.
     */
    @Test
    void ownershipHoldsEvenWhenThePostIsHiddenBySoftDelete() {
        HttpGraphQlTester author = asAuthor();
        String meu = createPost(author, "Some e volta", "conteúdo");
        author.document("mutation D($id: ID!) { deletePost(id: $id) }").variable("id", meu).execute();

        expectForbidden(as(KeycloakContainerConfig.PROMOTED_USERNAME)
                .document("mutation R($id: ID!) { restorePost(id: $id) { id } }")
                .variable("id", meu).execute());
    }

    private void expectForbidden(GraphQlTester.Response response) {
        response.errors()
                .expect(error -> "FORBIDDEN".equals(String.valueOf(error.getExtensions().get("classification"))))
                .verify();
    }
}
