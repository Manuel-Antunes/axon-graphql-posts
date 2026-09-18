package dev.manuelantunes.axonposts.nativeimage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.support.GraphQl;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Que a autorização sobrevive à compilação AOT.
 *
 * <h2>O que este arquivo NÃO faz</h2>
 * Não repete a varredura de {@code AuthorizationE2ETest}, que é parametrizada sobre a lista de
 * operações de escrita e é <b>o</b> guarda de "mutation nova sem {@code @RolesAllowed}". Duas listas
 * divergiriam, e a daqui divergiria em silêncio. O que se afirma aqui é outra coisa: que a cadeia de
 * interceptadores — {@code quarkus.http.auth.proactive=false}, o {@code @RolesAllowed} no método e o
 * {@code ErrorTranslationInterceptor} em {@code PLATFORM_BEFORE + 100} — continua produzindo os
 * mesmos códigos no binário nativo.
 */
@QuarkusIntegrationTest
class AuthorizationNativeIT {

    private static final String CREATE = """
            mutation { createPost(input: {title: "x", content: "y"}) { id } }""";

    /** Token ausente vira erro de GraphQL normal, não HTTP 401 — é a consequência do proactive=false. */
    @Test
    void writingWithoutATokenIsUnauthorized() {
        var response = GraphQl.anonymous().attempt(CREATE);

        assertThat(response.errorCode()).isEqualTo("UNAUTHORIZED");
        assertThat(response.errorMessage()).isNotBlank();
    }

    /** Autenticado sem a role: o código muda, e a mensagem continua existindo. */
    @Test
    void writingWithoutTheAuthorRoleIsForbidden() {
        var response = GraphQl.asReader().attempt(CREATE);

        assertThat(response.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(response.errorMessage()).isNotBlank();
    }

    /** As queries públicas continuam públicas no mesmo POST em que a escrita exige token. */
    @Test
    void readingStaysPublic() {
        java.util.Map<String, Object> posts = GraphQl.anonymous()
                .execute("{ posts(first: 1) { pageInfo { hasNextPage } } }")
                .get("posts");

        assertThat(posts).containsKey("pageInfo");
    }
}
