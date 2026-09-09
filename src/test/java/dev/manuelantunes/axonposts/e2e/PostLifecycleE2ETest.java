package dev.manuelantunes.axonposts.e2e;

import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O ciclo de vida inteiro de um Post, pela API: criar → tag padrão → editar → apagar → restaurar.
 *
 * <h2>O que só aparece aqui</h2>
 * A orquestração do {@code AssignDefaultTagOnPostCreated} — dois commands encadeados no
 * {@code AFTER_COMMIT} do primeiro — não tem como ser testada com o {@code AxonTestFixture}, que monta um
 * command por vez. O que ela tem de interessante é justamente a <b>ordem</b>: a mutation só responde
 * depois de a tag estar atribuída, e por isso um post recém-criado já chega na versão 2.
 * <p>
 * O mesmo vale para o par apagar/restaurar: o {@code @SQLRestriction} some com a linha para o JPA, e é o
 * event sourcing que torna o restore possível. Nada disso é observável sem o banco e o Axon reais.
 */
class PostLifecycleE2ETest extends AbstractGraphQlE2ETest {

    // um selection set, não um documento: o prefix/suffix dá ao IDE o contexto que falta
    //language=GraphQL prefix={posts{edges{node{ suffix=}}}}
    private static final String POST_FIELDS = """
            id title content version
            author { __typename name }
            tags(first: 5) { edges { node { name } } pageInfo { hasNextPage } }
            """;

    @Test
    void aNewPostArrivesAlreadyTaggedAtVersionTwo() {
        asAuthor().document(
                //language=GraphQL
                "mutation { createPost(input: {title: \"Nasce\", content: \"c\"}) { " + POST_FIELDS + " } }")
                .execute()
                .path("createPost.title").entity(String.class).isEqualTo("Nasce")
                // 1 = criado, 2 = tag padrão atribuída. A mutation espera o AFTER_COMMIT completar
                .path("createPost.version").entity(Integer.class).isEqualTo(2)
                .path("createPost.author.__typename").entity(String.class).isEqualTo("Author")
                .path("createPost.tags.edges").entityList(Object.class).hasSize(1)
                .path("createPost.tags.edges[0].node.name").entity(String.class).isEqualTo("Untagged");
    }

    @Test
    void twoPostsShareTheSameDefaultTag() {
        HttpGraphQlTester author = asAuthor();
        createPost(author, "Primeiro", "c");
        createPost(author, "Segundo", "c");

        // a tag padrão é criada uma vez e reusada: o handler procura por nome antes de despachar CreateTag
        assertThat(jdbc.queryForObject("select count(*) from tags", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from post_tags", Integer.class)).isEqualTo(2);
    }

    @Test
    void updatingKeepsTheTagsAndBumpsTheVersion() {
        HttpGraphQlTester author = asAuthor();
        String id = createPost(author, "Título original", "conteúdo");

        author.document(
                //language=GraphQL
                """
                        mutation Editar($id: ID!) {
                          updatePost(input: {id: $id, title: "Título editado"}) { title content version
                            tags(first: 5) { edges { node { name } } } }
                        }""")
                .variable("id", id)
                .execute()
                .path("updatePost.title").entity(String.class).isEqualTo("Título editado")
                // content veio null no input: "não mexer", não "apagar"
                .path("updatePost.content").entity(String.class).isEqualTo("conteúdo")
                .path("updatePost.version").entity(Integer.class).isEqualTo(3)
                .path("updatePost.tags.edges").entityList(Object.class).hasSize(1);
    }

    @Test
    void anUpdateWithoutChangesIsRefused() {
        HttpGraphQlTester author = asAuthor();
        String id = createPost(author, "Igual", "conteúdo");

        author.document(
                //language=GraphQL
                """
                        mutation Editar($id: ID!) {
                          updatePost(input: {id: $id, title: "Igual"}) { version }
                        }""")
                .variable("id", id)
                .execute()
                .errors()
                .expect(error -> "BAD_REQUEST".equals(String.valueOf(error.getExtensions().get("classification"))))
                .verify();
    }

    @Test
    void deleteHidesThePostAndRestoreBringsItBackWhole() {
        HttpGraphQlTester author = asAuthor();
        String id = createPost(author, "Vai e volta", "conteúdo");

        assertThat(postCount()).isEqualTo(1);

        author.document(
                //language=GraphQL
                "mutation Apagar($id: ID!) { deletePost(id: $id) }")
                .variable("id", id).execute()
                .path("deletePost").entity(Boolean.class).isEqualTo(true);

        // some das consultas...
        assertThat(postCount()).isZero();
        anonymous.document(
                //language=GraphQL
                "query Um($id: ID!) { post(id: $id) { id } }")
                .variable("id", id).execute()
                .path("post").valueIsNull();

        // ...mas a linha continua no banco, marcada
        assertThat(jdbc.queryForObject(
                "select count(*) from posts where id = ? and deleted_at is not null", Integer.class, id))
                .isEqualTo(1);

        author.document(
                //language=GraphQL
                "mutation Restaurar($id: ID!) { restorePost(id: $id) { " + POST_FIELDS + " } }")
                .variable("id", id).execute()
                // 2 = criado+tag, 3 = apagado, 4 = restaurado. Apagar e restaurar são fatos, e versionam
                .path("restorePost.version").entity(Integer.class).isEqualTo(4)
                .path("restorePost.title").entity(String.class).isEqualTo("Vai e volta")
                // o que importa do restore: o resto do agregado voltou junto
                .path("restorePost.tags.edges[0].node.name").entity(String.class).isEqualTo("Untagged")
                .path("restorePost.author.name").entity(String.class).isEqualTo("Manuel Antunes");

        assertThat(postCount()).isEqualTo(1);
    }

    @Test
    void theSoftDeleteGuardsAreEnforcedThroughTheApi() {
        HttpGraphQlTester author = asAuthor();
        String id = createPost(author, "Guardas", "c");

        // restaurar o que está vivo
        expectBadRequest(author.document(
                //language=GraphQL
                "mutation R($id: ID!) { restorePost(id: $id) { id } }")
                .variable("id", id).execute());

        author.document(
                //language=GraphQL
                "mutation D($id: ID!) { deletePost(id: $id) }").variable("id", id).execute();

        // apagar o que já está apagado
        expectBadRequest(author.document(
                //language=GraphQL
                "mutation D($id: ID!) { deletePost(id: $id) }")
                .variable("id", id).execute());
    }

    @Test
    void cursorPaginationWalksTheWholeList() {
        HttpGraphQlTester author = asAuthor();
        createPost(author, "A", "c");
        createPost(author, "B", "c");
        createPost(author, "C", "c");

        String cursor = anonymous.document(
                //language=GraphQL
                "{ posts(first: 2) { edges { cursor node { title } } pageInfo { hasNextPage endCursor } } }")
                .execute()
                .path("posts.edges").entityList(Object.class).hasSize(2)
                .path("posts.edges[0].node.title").entity(String.class).isEqualTo("A")
                .path("posts.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true)
                .path("posts.pageInfo.endCursor").entity(String.class).get();

        anonymous.document(
                //language=GraphQL
                "query P($after: String!) { posts(first: 2, after: $after) { edges { node { title } } pageInfo { hasNextPage } } }")
                .variable("after", cursor)
                .execute()
                .path("posts.edges").entityList(Object.class).hasSize(1)
                .path("posts.edges[0].node.title").entity(String.class).isEqualTo("C")
                .path("posts.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false);
    }

    private void expectBadRequest(GraphQlTester.Response response) {
        response.errors()
                .expect(error -> "BAD_REQUEST".equals(String.valueOf(error.getExtensions().get("classification"))))
                .verify();
    }

    private int postCount() {
        return anonymous.document(
                //language=GraphQL
                "{ posts(first: 50) { edges { node { id } } } }")
                .execute()
                .path("posts.edges").entityList(Object.class).get().size();
    }
}
