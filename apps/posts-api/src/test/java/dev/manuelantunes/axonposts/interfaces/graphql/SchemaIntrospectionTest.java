package dev.manuelantunes.axonposts.interfaces.graphql;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SchemaIntrospectionTest {
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
