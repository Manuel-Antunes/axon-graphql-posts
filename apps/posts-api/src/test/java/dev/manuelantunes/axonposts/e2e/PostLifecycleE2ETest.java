package dev.manuelantunes.axonposts.e2e;

import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import io.quarkus.test.junit.QuarkusTest;
import dev.manuelantunes.axonposts.support.GraphQl;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class PostLifecycleE2ETest extends AbstractGraphQlE2ETest {
    private static final String POST_FIELDS = """
            id title content version
            author { __typename name }
            tags(first: 5) { edges { node { name } } pageInfo { hasNextPage } }
            """;

    @Test
    void aNewPostArrivesAlreadyTaggedAtVersionTwo() {
        var response = asAuthor().execute(
                "mutation { createPost(input: {title: \"Nasce\", content: \"c\"}) { " + POST_FIELDS + " } }");

        assertThat(response.string("createPost.title")).isEqualTo("Nasce");
        assertThat(response.integer("createPost.version")).isEqualTo(2);
        assertThat(response.string("createPost.author.__typename")).isEqualTo("Author");
        assertThat(response.list("createPost.tags.edges")).hasSize(1);
        assertThat(response.string("createPost.tags.edges[0].node.name")).isEqualTo("Untagged");
    }

    @Test
    void twoPostsShareTheSameDefaultTag() {
        GraphQl author = asAuthor();
        author.createPost("Primeiro", "c");
        author.createPost("Segundo", "c");

        assertThat(count("select count(*) from tags")).isEqualTo(1);
        assertThat(count("select count(*) from post_tags")).isEqualTo(2);
    }

    @Test
    void updatingKeepsTheTagsAndBumpsTheVersion() {
        GraphQl author = asAuthor();
        String id = author.createPost("Título original", "conteúdo");

        var response = author.execute("""
                mutation Editar($id: ID!) {
                  updatePost(input: {id: $id, title: "Título editado"}) { title content version
                    tags(first: 5) { edges { node { name } } } }
                }""", "id", id);

        assertThat(response.string("updatePost.title")).isEqualTo("Título editado");
        assertThat(response.string("updatePost.content")).isEqualTo("conteúdo");
        assertThat(response.integer("updatePost.version")).isEqualTo(3);
        assertThat(response.list("updatePost.tags.edges")).hasSize(1);
    }

    @Test
    void anUpdateWithoutChangesIsRefused() {
        GraphQl author = asAuthor();
        String id = author.createPost("Igual", "conteúdo");

        assertThat(author.attempt("""
                mutation Editar($id: ID!) {
                  updatePost(input: {id: $id, title: "Igual"}) { version }
                }""", "id", id).errorCode()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void deleteHidesThePostAndRestoreBringsItBackWhole() {
        GraphQl author = asAuthor();
        String id = author.createPost("Vai e volta", "conteúdo");

        assertThat(postCount()).isEqualTo(1);

        assertThat(author.execute("mutation Apagar($id: ID!) { deletePost(id: $id) }", "id", id)
                .bool("deletePost")).isTrue();

        assertThat(postCount()).isZero();
        assertThat(anonymous.execute("query Um($id: ID!) { post(id: $id) { id } }", "id", id)
                .isNull("post")).isTrue();

        assertThat(count("select count(*) from posts where id = ? and deleted_at is not null", id)).isEqualTo(1);

        var restored = author.execute(
                "mutation Restaurar($id: ID!) { restorePost(id: $id) { " + POST_FIELDS + " } }", "id", id);

        assertThat(restored.integer("restorePost.version")).isEqualTo(4);
        assertThat(restored.string("restorePost.title")).isEqualTo("Vai e volta");
        assertThat(restored.string("restorePost.tags.edges[0].node.name")).isEqualTo("Untagged");
        assertThat(restored.string("restorePost.author.name")).isEqualTo("Manuel Antunes");

        assertThat(postCount()).isEqualTo(1);
    }

    @Test
    void theSoftDeleteGuardsAreEnforcedThroughTheApi() {
        GraphQl author = asAuthor();
        String id = author.createPost("Guardas", "c");

        assertThat(author.attempt("mutation R($id: ID!) { restorePost(id: $id) { id } }", "id", id)
                .errorCode()).isEqualTo("BAD_REQUEST");

        author.execute("mutation D($id: ID!) { deletePost(id: $id) }", "id", id);

        assertThat(author.attempt("mutation D($id: ID!) { deletePost(id: $id) }", "id", id)
                .errorCode()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void cursorPaginationWalksTheWholeList() {
        GraphQl author = asAuthor();
        author.createPost("A", "c");
        author.createPost("B", "c");
        author.createPost("C", "c");

        var first = anonymous.execute(
                "{ posts(first: 2) { edges { cursor node { title } } pageInfo { hasNextPage endCursor } } }");

        assertThat(first.list("posts.edges")).hasSize(2);
        assertThat(first.string("posts.edges[0].node.title")).isEqualTo("A");
        assertThat(first.bool("posts.pageInfo.hasNextPage")).isTrue();
        String cursor = first.string("posts.pageInfo.endCursor");

        var second = anonymous.execute("""
                query P($after: String!) {
                  posts(first: 2, after: $after) { edges { node { title } }
                    pageInfo { hasNextPage hasPreviousPage } }
                }""", "after", cursor);

        assertThat(second.list("posts.edges")).hasSize(1);
        assertThat(second.string("posts.edges[0].node.title")).isEqualTo("C");
        assertThat(second.bool("posts.pageInfo.hasNextPage")).isFalse();
        assertThat(second.bool("posts.pageInfo.hasPreviousPage")).isTrue();
    }

    @Test
    void theConnectionRefusesAnOversizedPageAndAForeignCursor() {
        assertThat(anonymous.attempt("{ posts(first: 5000) { edges { cursor } } }").errorCode())
                .isEqualTo("BAD_REQUEST");

        String tagCursor = asAuthor().execute("""
                mutation { createPost(input: {title: "Com tag", content: "c"}) {
                  tags(first: 1) { edges { cursor } } } }""")
                .string("createPost.tags.edges[0].cursor");

        assertThat(anonymous.attempt("query P($c: String!) { posts(first: 2, after: $c) { edges { cursor } } }",
                "c", tagCursor).errorCode()).isEqualTo("BAD_REQUEST");
    }

    private int postCount() {
        return anonymous.execute("{ posts(first: 50) { edges { node { id } } } }").list("posts.edges").size();
    }
}
