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

/**
 * A matriz de autorização: quem pode o quê, em cada mutation que exige papel.
 *
 * <h2>Por que uma tabela e não um teste por caso</h2>
 * As quatro mutations de escrita têm exatamente a mesma regra ({@code @RolesAllowed("author")}), e o risco
 * real é uma delas <b>esquecer</b> a anotação. Um teste por mutation esconderia isso — o que falta é o
 * caso que ninguém escreveu. Enumerando as operações numa fonte de argumentos, acrescentar uma mutation
 * sem acrescentar a linha aqui fica visível na revisão.
 *
 * <h2>{@code UNAUTHORIZED} e {@code FORBIDDEN} são respostas diferentes</h2>
 * No projeto Spring as duas recusas chegavam como {@code FORBIDDEN}, porque a cadeia era {@code permitAll}
 * e quem barrava era sempre o {@code @PreAuthorize}. Aqui o Quarkus distingue: sem token é
 * "identifique-se", com token e sem a role é "você não pode". É a resposta mais correta das duas, e é a
 * que o cliente precisa para decidir se manda a pessoa logar ou mostra "sem permissão".
 * <p>
 * Os códigos são os <b>do projeto</b>, em maiúsculas, e não os que o SmallRye derivaria do nome da classe
 * do Quarkus ({@code unauthorized}/{@code forbidden}). Quem os produz é o {@code ErrorTranslationInterceptor},
 * que roda por fora dos interceptadores de segurança justamente para alcançá-los — antes disso a recusa
 * saía com {@code "message": null}, porque as exceções do Quarkus não têm mensagem.
 */
@QuarkusTest
class AuthorizationE2ETest extends AbstractGraphQlE2ETest {

    @Inject
    UserRepository users;

    /** As mutations que exigem a role de autor, cada uma com um documento que não depende de estado. */
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

        // o @RolesAllowed barra antes de o CurrentUser rodar: uma recusa não deixa linha no banco
        assertThat(users.findByEmail(Email.of(Realm.READER_USERNAME))).isEmpty();
        assertThat(count("select count(*) from users")).isZero();
    }

    /**
     * A recusa <b>diz</b> alguma coisa — que é o que faltava quando as exceções do Quarkus chegavam ao
     * cliente sem passar pela tradução.
     * <p>
     * {@code io.quarkus.security.UnauthorizedException} não tem mensagem. Listá-la em
     * {@code show-runtime-exception-message} não a inventava: publicava {@code "message": null}, que é
     * pior que "System Error" — parece um bug da aplicação. Este teste é o que impede a volta disso.
     */
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

        // queries não exigem token: é o que permite a home do blog funcionar deslogado
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

        // e as duas contas ficaram ligadas ao provedor certo
        assertThat(users.findByEmail(Email.of(Realm.AUTHOR_USERNAME)))
                .hasValueSatisfying(user -> assertThat(user.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue());
    }

    /**
     * A regra que faltava: ser autor autoriza a escrever, não a escrever no alheio.
     * <p>
     * A checagem está no <b>domínio</b> ({@code Post.assertWrittenBy}), e não no resolver, porque depende
     * do estado do agregado — invariante, não permissão. Por isso vale venha o command de onde vier, e por
     * isso este teste percorre as três mutations.
     */
    @Test
    void anAuthorCannotTouchAnotherAuthorsPost() {
        String alheio = asAuthor().createPost("Do Manuel", "conteúdo");
        GraphQl outro = as(Realm.PROMOTED_USERNAME);

        assertThat(outro.attempt("mutation E($id: ID!) { updatePost(input: {id: $id, title: \"x\"}) { id } }",
                "id", alheio).errorCode()).isEqualTo("FORBIDDEN");
        assertThat(outro.attempt("mutation D($id: ID!) { deletePost(id: $id) }", "id", alheio).errorCode())
                .isEqualTo("FORBIDDEN");

        // e o post continua intacto
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

    /**
     * O caso mais delicado: um post apagado é invisível para o JPA, e ainda assim a checagem de dono
     * funciona — o agregado vem do stream, com o {@code author} reconstituído do {@code PostCreatedEvent}.
     */
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
