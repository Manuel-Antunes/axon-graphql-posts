package dev.manuelantunes.axonposts.interfaces.graphql;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O contrato de <b>subgraph</b>: o que o roteador lê deste serviço antes de compor o supergraph.
 *
 * <h2>Por que ler o {@code _service { sdl }} e não introspecção</h2>
 * A introspecção não devolve diretivas aplicadas — {@code @key} e {@code @shareable} simplesmente não
 * aparecem nela. A composição da Federação é feita sobre o SDL do {@code _service}, e é por isso que o
 * teste lê exatamente o que o {@code rover} leria. Um schema que introspecciona bem e não compõe é a
 * falha que este teste existe para pegar.
 *
 * <h2>É o guarda de três coisas que falham em silêncio</h2>
 * <ul>
 *   <li>a federação <b>ligada</b> — desligada, o SDL sai íntegro, sem {@code _entities}, e só a
 *       composição reclama, longe daqui;</li>
 *   <li>o {@code @link} com a versão <b>fixada</b> — sem ele o subgraph é lido como Federação 1;</li>
 *   <li>os {@code import} do {@code @link} — sem eles as diretivas saem como
 *       {@code @federation__key}, que compõe igual e não se lê igual.</li>
 * </ul>
 */
@QuarkusTest
class FederationSchemaTest {

    private static final String FEDERATION_SPEC = "https://specs.apollo.dev/federation/v2.7";

    private static String subgraphSdl() {
        return RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("query", "{ _service { sdl } }"))
                .post("/graphql")
                .then().statusCode(200)
                .extract().jsonPath().getString("data._service.sdl");
    }

    private static String servedSdl() {
        return RestAssured.get("/graphql/schema.graphql").then().statusCode(200).extract().asString();
    }

    @Test
    void theSubgraphAnnouncesTheFederationSpecItSpeaks() {
        assertThat(subgraphSdl())
                .as("sem @link no bloco schema o subgraph é composto como Federação 1")
                .contains("schema @link(")
                .contains("url : \"" + FEDERATION_SPEC + "\"");
    }

    @Test
    void theDirectivesActuallyUsedComeInUnprefixed() {
        String sdl = subgraphSdl();
        assertThat(sdl)
                .as("@key e @shareable estão no import do @link, então saem com o nome curto")
                .contains("import : [\"@key\", \"@shareable\"]")
                .doesNotContain("@federation__key")
                .doesNotContain("@federation__shareable");
        assertThat(sdl)
                .as("as que NÃO se usa continuam prefixadas — é assim que se vê o que foi importado")
                .contains("federation__external");
    }

    @Test
    void everyEntityCarriesItsKey() {
        assertThat(subgraphSdl())
                .contains("type Post @key(fields : \"id\")")
                .contains("type Tag @key(fields : \"id\")")
                .contains("type Author implements User @key(fields : \"id\")")
                .contains("type Reader implements User @key(fields : \"id\")")
                .as("interface de entidade: é o que permite @interfaceObject num subgraph vizinho")
                .contains("interface User @key(fields : \"id\")");
    }

    @Test
    void theEntityUnionListsExactlyTheResolvableTypes() {
        // o _Entity é montado pelo transform a partir de quem tem @key; se um @key sumir, some daqui
        assertThat(subgraphSdl())
                .contains("union _Entity = Author | Post | Reader | Tag")
                .contains("_entities(representations: [_Any!]!): [_Entity]!")
                .contains("_service: _Service!");
    }

    @Test
    void pageInfoIsTheOnlySharedValueType() {
        String sdl = subgraphSdl();
        assertThat(sdl)
                .as("todo subgraph que pagina define o seu PageInfo; sem @shareable a composição recusa")
                .contains("type PageInfo @shareable");
        assertThat(sdl)
                .as("as connections carregam entidades daqui — se outro subgraph as definisse seria conflito")
                .contains("type PostConnection {")
                .contains("type TagConnection {");
    }

    @Test
    void theServedSdlSaysTheSameThingAsTheServiceField() {
        // o arquivo versionado na raiz sai de /graphql/schema.graphql; ele só descreve o mesmo subgraph
        // se schema-include-directives E schema-include-schema-definition estiverem ligados
        assertThat(servedSdl())
                .contains("schema @link(")
                .contains(FEDERATION_SPEC)
                .contains("type Post @key(fields : \"id\")")
                .contains("type PageInfo @shareable");
    }
}
