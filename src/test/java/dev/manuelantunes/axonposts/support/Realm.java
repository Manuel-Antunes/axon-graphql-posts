package dev.manuelantunes.axonposts.support;

/**
 * Constantes do realm de teste — as mesmas que o {@code docker-compose.yml} importa.
 * <p>
 * O arquivo do realm vem de {@code docker/keycloak/}, posto no classpath pelo {@code <resources>} do
 * {@code pom.xml} e apontado por {@code quarkus.keycloak.devservices.realm-path}: não há uma segunda
 * cópia para divergir. Se o realm mudar (um cliente, uma role, um usuário), os testes mudam junto sem
 * ninguém precisar lembrar.
 *
 * <h2>O que sumiu em relação ao projeto Spring</h2>
 * A classe {@code Containers}, com o {@code static} que subia Postgres e Keycloak em paralelo e o
 * {@code registerProperties} que injetava as URLs, e a {@code KeycloakContainerConfig}, que construía o
 * container. O Dev Services do Quarkus faz as três coisas — sobe, importa o realm e configura o
 * {@code quarkus.oidc.auth-server-url} — sem uma linha de teste.
 * <p>
 * Sobrou <b>isto</b>: os nomes que os testes precisam citar.
 */
public final class Realm {

    public static final String CLIENT_ID = "axon-posts-api";
    public static final String PASSWORD = "segredo123";

    /** Tem a role {@code author} no realm. */
    public static final String AUTHOR_USERNAME = "manuel@example.com";

    /** Não tem role nenhuma: é o {@code Reader}. */
    public static final String READER_USERNAME = "leitor@example.com";

    /** Tem a role {@code author}; existe para exercitar a promoção {@code Reader} → {@code Author}. */
    public static final String PROMOTED_USERNAME = "promovido@example.com";

    private Realm() {
    }
}
