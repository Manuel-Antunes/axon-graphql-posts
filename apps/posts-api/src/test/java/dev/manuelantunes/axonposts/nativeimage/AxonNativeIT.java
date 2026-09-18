package dev.manuelantunes.axonposts.nativeimage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.support.GraphQl;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Os caminhos do Axon que dependem de reflexão, verificados <b>contra o binário nativo</b>.
 *
 * <h2>Por que existe, se os {@code *E2ETest} já cobrem isso na JVM</h2>
 * Porque em GraalVM nada disso é dado. A extensão do Axon não emite metadado de native, e o
 * {@code axon-native-support} deste repositório é que o deriva das anotações. Se um build step
 * regredir, a JVM continua verde e <b>só estes testes falham</b> — e do jeito mais enganoso possível:
 * a aplicação sobe, serve o schema, e as mutations respondem {@code no-handler-for-command}.
 *
 * <h2>Caixa-preta, e o que isso custa</h2>
 * {@code @QuarkusIntegrationTest} roda contra o executável, sem CDI: não há {@code @Inject} de
 * {@code CommandGateway}, {@code UserRepository} nem {@code EntityManagerFactory}. Por isso este
 * arquivo cobre menos que a suíte da JVM, e duas coisas não têm equivalente aqui: as asserções de
 * custo de {@code BatchLoadingE2ETest}/{@code FederationEntitiesE2ETest} (leem o {@code Statistics} do
 * Hibernate de dentro do processo) e a transição de promoção de {@code IdentityProvisioningE2ETest}
 * (despacha commands em processo).
 * <p>
 * Cada teste cria os próprios dados com id novo, então não há {@code truncate} entre métodos — o que
 * também é consequência de não haver {@code DataSource} para injetar.
 */
@QuarkusIntegrationTest
class AxonNativeIT {

    /**
     * O encadeamento inteiro numa asserção: command handler → evento → processor subscribing →
     * a saga de tagueamento → {@code onAfterCommit} → segundo command. Versão 2 com a
     * tag é a prova de que os quatro handlers foram <b>invocados por reflexão</b> em AOT.
     */
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

    /**
     * O teste mais profundo: {@code updatePost} <b>reidrata o agregado do event store</b>, reaplicando
     * o stream pelos {@code @EventSourcingHandler}. É o caminho que falhava com
     * {@code No suitable ParameterResolver found} antes dos {@code ServiceProviderBuildItem}.
     */
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

    /** {@code restorePost} só funciona porque o agregado é reconstruído dos eventos. */
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

    /** Query bus + read model + o lote de tags e autor, pelo caminho público. */
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

    /**
     * Os dois tipos concretos do agregado polimórfico. O Axon fixa o tipo na criação e o instancia por
     * reflexão; o {@code registerAnnotatedTypes} os registra a partir de
     * {@code @EventSourcedEntity(concreteTypes = ...)}.
     */
    @Test
    void bothConcreteTypesOfThePolymorphicUserResolve() {
        assertThat(GraphQl.asAuthor().execute("{ me { __typename } }").string("me.__typename"))
                .isEqualTo("Author");
        assertThat(GraphQl.asReader().execute("{ me { __typename } }").string("me.__typename"))
                .isEqualTo("Reader");
    }

    /** O subgraph continua subgraph: {@code _service} com as diretivas e {@code _entities} resolvendo. */
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
