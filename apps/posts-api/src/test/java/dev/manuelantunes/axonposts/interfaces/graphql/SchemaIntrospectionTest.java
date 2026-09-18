package dev.manuelantunes.axonposts.interfaces.graphql;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A introspecção do schema funciona — que é o que a GraphiQL e todo gerador de cliente fazem antes de
 * qualquer outra coisa.
 *
 * <h2>Por que este teste existe</h2>
 * Porque o teto de profundidade de query do SmallRye vem ligado com o valor <b>10</b> por padrão, e a
 * consulta de introspecção tem profundidade 15. Com o padrão, a aplicação sobe, o SDL em
 * {@code /graphql/schema.graphql} continua sendo servido, toda query normal responde — e a GraphiQL abre
 * <b>em branco</b>, com um único erro de {@code ExecutionAborted}. Era invisível para o resto da suíte:
 * os testes ponta a ponta mandam queries rasas, e o {@code RelaySchemaTest} lê o SDL por HTTP, não por
 * introspecção.
 * <p>
 * O que trava aqui é o {@code quarkus.smallrye-graphql.instrumentation-query-depth} do
 * {@code application.properties}. Baixá-lo de volta para menos de 15 derruba este teste, e não mais a
 * primeira pessoa que abrir a UI.
 */
@QuarkusTest
class SchemaIntrospectionTest {

    /** A consulta padrão do graphql-js, que é a que a GraphiQL manda ao abrir. */
    private static final String INTROSPECTION = """
            query IntrospectionQuery {
              __schema {
                queryType { name } mutationType { name } subscriptionType { name }
                types { ...FullType }
                directives { name locations args { ...InputValue } }
              }
            }
            fragment FullType on __Type {
              kind name description
              fields(includeDeprecated: true) {
                name description args { ...InputValue } type { ...TypeRef }
                isDeprecated deprecationReason
              }
              inputFields { ...InputValue }
              interfaces { ...TypeRef }
              enumValues(includeDeprecated: true) { name description isDeprecated deprecationReason }
              possibleTypes { ...TypeRef }
            }
            fragment InputValue on __InputValue { name description type { ...TypeRef } defaultValue }
            fragment TypeRef on __Type {
              kind name
              ofType { kind name ofType { kind name ofType { kind name ofType { kind name
                ofType { kind name ofType { kind name ofType { kind name } } } } } } }
            }""";

    @Test
    void theSchemaCanBeIntrospected() {
        JsonPath response = post(INTROSPECTION);

        assertThat(errorsOf(response))
                .as("a introspecção não pode ter erro — é o primeiro que a GraphiQL faz")
                .isNullOrEmpty();
        assertThat(response.getList("data.__schema.types.name", String.class))
                .contains("Post", "PostConnection", "PostEdge", "PageInfo", "User", "Author", "Reader");
    }

    /**
     * O outro lado do mesmo teto: a query mais funda que este schema oferece precisa passar.
     * <p>
     * São onze níveis — {@code posts → edges → node → author → posts → edges → node → tags → edges →
     * node → name} —, já acima do padrão de 10. Um cliente Relay legítimo escreve isso.
     */
    @Test
    void theDeepestLegitimateQueryIsNotRefused() {
        JsonPath response = post("""
                { posts(first: 2) { edges { node { author { posts(first: 2) { edges { node {
                    tags(first: 2) { edges { node { name } } } } } } } } } } }""");

        assertThat(errorsOf(response)).isNullOrEmpty();
    }

    private static java.util.List<?> errorsOf(JsonPath response) {
        return response.getList("errors");
    }

    private static JsonPath post(String document) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("query", document))
                .post("/graphql")
                .then().statusCode(200)
                .extract().jsonPath();
    }
}
