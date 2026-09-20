package dev.manuelantunes.axonposts.nativeimage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.support.GraphQl;
import dev.manuelantunes.axonposts.support.RequiresNativeArtifact;
import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
@RequiresNativeArtifact
class AxonNativeIT {
    @Test
    void aNewPostArrivesAlreadyTaggedAtVersionTwo() {
        var response = GraphQl.asAuthor().execute("""
                mutation {
                  createPost(input: {title: "Nativo", content: "conteúdo"}) {
                    id version tags { edges { node { name } } }
                  }
                }""");

        assertThat(response.integer("createPost.version")).isEqualTo(2);
        assertThat(response.string("createPost.tags.edges[0].node.name")).isEqualTo("Untagged");
    }

    @Test
    void updatingRehydratesTheAggregateFromTheEventStream() {
        GraphQl author = GraphQl.asAuthor();
        String id = author.createPost("Para editar", "conteúdo");

        var updated = author.execute("""
                mutation Editar($id: ID!) {
                  updatePost(input: {id: $id, title: "Título editado"}) { title version }
                }""", "id", id);

        assertThat(updated.integer("updatePost.version")).isEqualTo(3);
        assertThat(updated.string("updatePost.title")).isEqualTo("Título editado");
    }

    @Test
    void deletingAndRestoringGoesThroughTheEventStream() {
        GraphQl author = GraphQl.asAuthor();
        String id = author.createPost("Vai e volta", "conteúdo");

        assertThat(author.execute("mutation A($id: ID!) { deletePost(id: $id) }", "id", id)
                .bool("deletePost")).isTrue();
        assertThat(GraphQl.anonymous().execute("query U($id: ID!) { post(id: $id) { id } }", "id", id)
                .isNull("post")).isTrue();

        assertThat(author.execute("mutation R($id: ID!) { restorePost(id: $id) { id } }", "id", id)
                .string("restorePost.id")).isEqualTo(id);
    }

    @Test
    void theQueryBusServesThePublicRead() {
        String id = GraphQl.asAuthor().createPost("Para ler", "conteúdo");

        var post = GraphQl.anonymous().execute("""
                query Um($id: ID!) {
                  post(id: $id) { id title author { id email } tags { edges { node { name } } } }
                }""", "id", id);

        assertThat(post.string("post.id")).isEqualTo(id);
        assertThat(post.string("post.author.email")).isEqualTo(dev.manuelantunes.axonposts.support.Realm.AUTHOR_USERNAME);
    }

    @Test
    void bothConcreteTypesOfThePolymorphicUserResolve() {
        assertThat(GraphQl.asAuthor().execute("{ me { __typename } }").string("me.__typename"))
                .isEqualTo("Author");
        assertThat(GraphQl.asReader().execute("{ me { __typename } }").string("me.__typename"))
                .isEqualTo("Reader");
    }

    @Test
    void theSubgraphStillServesTheFederationContract() {
        assertThat(GraphQl.anonymous().execute("{ _service { sdl } }").string("_service.sdl"))
                .contains("@key");

        String id = GraphQl.asAuthor().createPost("Federado", "conteúdo");
        var entities = GraphQl.anonymous().execute("""
                query Ent($r: [_Any!]!) { _entities(representations: $r) { ... on Post { id } } }""",
                "r", java.util.List.of(java.util.Map.of("__typename", "Post", "id", id)));

        assertThat(entities.string("_entities[0].id")).isEqualTo(id);
    }
}
