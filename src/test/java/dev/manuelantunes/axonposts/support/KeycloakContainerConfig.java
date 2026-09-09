package dev.manuelantunes.axonposts.support;

import dasniko.testcontainers.keycloak.KeycloakContainer;

/**
 * Constantes do realm de teste — as mesmas que o {@code docker-compose.yml} importa.
 * <p>
 * O arquivo do realm vem de {@code docker/keycloak/}, adicionado ao classpath de teste pelo
 * {@code pom.xml}: não há uma segunda cópia para divergir. Se o realm mudar (um cliente, uma role, um
 * usuário), os testes mudam junto sem ninguém precisar lembrar.
 * <p>
 * Não há {@code @Bean} de container aqui de propósito. O Keycloak precisa estar de pé <b>antes</b> do
 * contexto Spring — o {@code issuer-uri} é resolvido na criação do decoder, que busca o
 * {@code openid-configuration} na partida —, então quem o inicia é um campo estático na classe de teste,
 * não o ciclo de vida de beans.
 */
public final class KeycloakContainerConfig {

    public static final String IMAGE = "quay.io/keycloak/keycloak:26.0";
    public static final String REALM_IMPORT = "/realm-axon-posts.json";

    public static final String REALM = "axon-posts";
    public static final String CLIENT_ID = "axon-posts-api";
    public static final String PASSWORD = "segredo123";

    public static final String AUTHOR_USERNAME = "manuel@example.com";
    public static final String READER_USERNAME = "leitor@example.com";
    public static final String PROMOTED_USERNAME = "promovido@example.com";

    private KeycloakContainerConfig() {
    }

    /** O container já configurado com o realm; quem o inicia é a classe de teste. */
    public static KeycloakContainer container() {
        return new KeycloakContainer(IMAGE).withRealmImportFile(REALM_IMPORT);
    }

    /** O issuer que a aplicação valida: a URL do container mais o realm. */
    public static String issuerUri(KeycloakContainer keycloak) {
        return keycloak.getAuthServerUrl() + "/realms/" + REALM;
    }
}
