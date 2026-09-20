package dev.manuelantunes.axonposts.nativeimage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.support.GraphQl;
import dev.manuelantunes.axonposts.support.RequiresNativeArtifact;
import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
@RequiresNativeArtifact
class AuthorizationNativeIT {
    private static final String CREATE = """
            mutation { createPost(input: {title: "x", content: "y"}) { id } }""";

    @Test
    void writingWithoutATokenIsUnauthorized() {
        var response = GraphQl.anonymous().attempt(CREATE);

        assertThat(response.errorCode()).isEqualTo("UNAUTHORIZED");
        assertThat(response.errorMessage()).isNotBlank();
    }

    @Test
    void writingWithoutTheAuthorRoleIsForbidden() {
        var response = GraphQl.asReader().attempt(CREATE);

        assertThat(response.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(response.errorMessage()).isNotBlank();
    }

    @Test
    void readingStaysPublic() {
        java.util.Map<String, Object> posts = GraphQl.anonymous()
                .execute("{ posts(first: 1) { pageInfo { hasNextPage } } }")
                .get("posts");

        assertThat(posts).containsKey("pageInfo");
    }
}
