package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O schema <b>gerado</b> carrega os tipos de cursor connection com os nomes da convenção Relay.
 *
 * <h2>Por que este teste existe</h2>
 * Porque o mecanismo que produz esses nomes é sutil e invisível em tempo de compilação.
 * {@link Connection} e {@link Edge} são genéricos, e um tipo genérico instanciado vira, no SmallRye,
 * {@code Connection_Post} — o nome do tipo mais um sufixo por argumento. O que evita isso são as
 * subclasses concretas de uma linha ({@link PostConnection}, {@link PostEdge}): para o construtor de
 * schema elas são tipos comuns, e um tipo comum leva o nome da classe, <b>sem</b> perder a resolução dos
 * genéricos herdados.
 * <p>
 * Nada no compilador garante isso. Trocar {@code List<E>} por {@code List<Edge<N>>} em
 * {@code Connection}, ou apagar a subclasse e devolver {@code Connection<PostView, PostEdge>} direto do
 * resolver, continua compilando e muda o contrato do schema em silêncio. É este teste que transforma a
 * mudança em build vermelho.
 *
 * <h2>O que ele substitui</h2>
 * O {@code GeneratedConnectionTypesTest} do projeto Spring, que mantinha um bloco de SDL escrito no fim do
 * {@code posts.graphqls} com o que o {@code ConnectionTypeDefinitionConfigurer} geraria — porque lá o
 * schema era um arquivo e o que o Spring montava em memória não existia para o IDE nem para geradores de
 * cliente.
 * <p>
 * Aqui o schema <b>é</b> gerado, e o Quarkus o serve como SDL em {@code /graphql/schema.graphql}. Não há
 * segunda definição para manter em dia: há um teste que lê a única que existe.
 */
@QuarkusTest
class RelaySchemaTest {

    private static String schema() {
        return RestAssured.get("/graphql/schema.graphql").then().statusCode(200).extract().asString();
    }

    @Test
    void theConnectionTypesFollowTheRelayNamingConvention() {
        String schema = schema();

        assertThat(schema)
                .as("os genéricos viraram tipos com nome de convenção, e não Connection_Post/Edge_Post")
                .contains("type PostConnection {")
                .contains("type PostEdge {")
                .contains("type TagConnection {")
                .contains("type TagEdge {")
                .contains("type PageInfo {")
                .doesNotContain("Connection_")
                .doesNotContain("Edge_");
    }

    @Test
    void anEdgeCarriesTheConcreteNodeType() {
        // é a prova de que a herança genérica foi resolvida: N virou Post, e não um tipo apagado
        assertThat(schema())
                .contains("node: Post!")
                .contains("node: Tag!")
                .contains("cursor: String!");
    }

    @Test
    void aConnectionPointsAtItsOwnEdgeType() {
        // e aqui que E virou PostEdge: se Connection declarasse List<Edge<N>>, sairia [Edge_Post]!
        assertThat(schema())
                .contains("edges: [PostEdge]!")
                .contains("edges: [TagEdge]!")
                .contains("pageInfo: PageInfo!");
    }

    @Test
    void everyPaginatedFieldTakesFirstAndAfter() {
        String schema = schema();

        assertThat(schema).contains("posts(");
        assertThat(schema).contains("tags(");
        assertThat(schema).contains("first: Int");
        assertThat(schema).contains("after: String");
    }

    /**
     * O outro contrato que nasce do código e não de um arquivo: a interface polimórfica.
     * <p>
     * No projeto Spring, {@code interface User} era escrito à mão no SDL e um
     * {@code ClassNameTypeResolver} num {@code @Bean} dizia qual classe Java era qual type. Aqui o
     * {@code @Name} de cada implementação responde as duas coisas — e este teste garante que a interface
     * não sumiu do schema, que é <b>exatamente</b> o que acontece quando um acessor dela perde o
     * {@code @Name}: sem campos, o modelo do SmallRye a descarta em silêncio.
     */
    @Test
    void theUserInterfaceIsInTheSchemaWithBothImplementations() {
        assertThat(schema())
                .contains("interface User {")
                .contains("type Author implements User {")
                .contains("type Reader implements User {");
    }
}
