package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

import static org.assertj.core.api.Assertions.assertThat;

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
                .contains("type PageInfo ")
                .doesNotContain("Connection_")
                .doesNotContain("Edge_");
    }

    @Test
    void anEdgeCarriesTheConcreteNodeType() {
        assertThat(schema())
                .contains("node: Post!")
                .contains("node: Tag!")
                .contains("cursor: String!");
    }

    @Test
    void aConnectionPointsAtItsOwnEdgeType() {
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

    @Test
    void theUserInterfaceIsInTheSchemaWithBothImplementations() {
        assertThat(schema())
                .contains("interface User ")
                .contains("type Author implements User ")
                .contains("type Reader implements User ");
    }
}
