package dev.manuelantunes.axonposts.support;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * Pega um token de verdade do Keycloak, pelo grant {@code password}.
 * <p>
 * É por isso que o realm importa o cliente com {@code directAccessGrantsEnabled}: sem ele, um teste
 * precisaria simular um navegador para completar o authorization code. Num cliente de produção esse grant
 * ficaria desligado.
 * <p>
 * O token que sai daqui é <b>o mesmo</b> que a aplicação receberia em produção — assinado pela chave do
 * realm, com {@code realm_access.roles} e {@code sub} reais. É o que faz este teste exercitar o
 * {@code KeycloakRealmRolesConverter} e o provisionamento de ponta a ponta, em vez de um token de mentira.
 */
public final class KeycloakTokens {

    private KeycloakTokens() {
    }

    public static String accessToken(KeycloakContainer keycloak, String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", KeycloakContainerConfig.CLIENT_ID);
        form.add("username", username);
        form.add("password", password);

        Map<?, ?> response = WebClient.create(keycloak.getAuthServerUrl())
                .post()
                .uri("/realms/{realm}/protocol/openid-connect/token", KeycloakContainerConfig.REALM)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("Keycloak não devolveu access_token para " + username);
        }
        return String.valueOf(response.get("access_token"));
    }
}
