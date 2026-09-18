package dev.manuelantunes.axonposts.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.ConfigProvider;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

/**
 * Cliente GraphQL dos testes ponta a ponta: um POST em {@code /graphql} com, ou sem, um
 * {@code Authorization: Bearer} de verdade.
 *
 * <h2>Token de verdade, e de onde ele vem</h2>
 * O Dev Services do Keycloak subiu um container com o realm importado e publicou a URL dele em
 * {@code quarkus.oidc.auth-server-url}. O token sai do grant {@code password} desse realm — é <b>o
 * mesmo</b> token que a aplicação receberia em produção, assinado pela chave do realm, com
 * {@code realm_access.roles} e {@code sub} reais.
 * <p>
 * É por isso que o realm importa o cliente com {@code directAccessGrantsEnabled}: sem ele, um teste
 * precisaria simular um navegador para completar o authorization code. Num cliente de produção esse grant
 * ficaria desligado.
 *
 * <h2>Por que RestAssured e não um GraphQlTester</h2>
 * Porque não existe um. O Spring publica o {@code HttpGraphQlTester}; do lado SmallRye o que há é o
 * protocolo, que é HTTP com um JSON de dois campos. O que esta classe acrescenta é o mínimo para os
 * testes lerem bem: caminhos relativos a {@code data} e um jeito de afirmar sobre {@code errors}.
 */
public final class GraphQl {

    /** Um token por usuário, reaproveitado: pedir um por requisição custaria um round-trip ao Keycloak. */
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    private final String token;

    private GraphQl(String token) {
        this.token = token;
    }

    /** Sem token: é como a home do blog chega. */
    public static GraphQl anonymous() {
        return new GraphQl(null);
    }

    /** Autenticado como um usuário do realm. */
    public static GraphQl as(String username) {
        return new GraphQl(TOKENS.computeIfAbsent(username, GraphQl::accessToken));
    }

    public static GraphQl asAuthor() {
        return as(Realm.AUTHOR_USERNAME);
    }

    public static GraphQl asReader() {
        return as(Realm.READER_USERNAME);
    }

    /** Executa e exige sucesso: qualquer entrada em {@code errors} falha o teste. */
    public GraphQlResponse execute(String document, Object... variables) {
        return post(document, asMap(variables)).assertNoErrors();
    }

    /** Executa sem exigir nada: para os casos em que o erro <b>é</b> o que se afirma. */
    public GraphQlResponse attempt(String document, Object... variables) {
        return post(document, asMap(variables));
    }

    /** Cria um post pelo caminho normal (mutation autenticada) e devolve o id. */
    public String createPost(String title, String content) {
        return execute("""
                mutation Criar($t: String!, $c: String!) {
                  createPost(input: {title: $t, content: $c}) { id }
                }""", "t", title, "c", content)
                .string("createPost.id");
    }

    private GraphQlResponse post(String document, Map<String, Object> variables) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", document);
        body.put("variables", variables);

        var request = RestAssured.given().contentType(ContentType.JSON).body(body);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        Response response = request.post("/graphql");
        return new GraphQlResponse(document, response.statusCode(), response.jsonPath());
    }

    /** Pares chave/valor achatados na chamada: {@code execute(doc, "id", x, "first", 2)}. */
    private static Map<String, Object> asMap(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("variáveis são pares chave/valor, recebidos " + pairs.length);
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            variables.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return variables;
    }

    private static String accessToken(String username) {
        String authServerUrl = ConfigProvider.getConfig()
                .getValue("quarkus.oidc.auth-server-url", String.class);

        String token = RestAssured.given()
                .baseUri(authServerUrl)
                .contentType(ContentType.URLENC)
                .formParam("grant_type", "password")
                .formParam("client_id", Realm.CLIENT_ID)
                .formParam("username", username)
                .formParam("password", Realm.PASSWORD)
                .post("/protocol/openid-connect/token")
                .then().statusCode(200)
                .extract().path("access_token");

        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Keycloak não devolveu access_token para " + username);
        }
        return token;
    }

    /**
     * Uma resposta GraphQL, com os caminhos relativos a {@code data}.
     * <p>
     * {@code response.string("posts.edges[0].node.title")} em vez de
     * {@code jsonPath.getString("data.posts.edges[0].node.title")} — é a mesma leitura que o
     * {@code HttpGraphQlTester} do Spring oferecia, e o que mantém os testes portados legíveis.
     */
    public static final class GraphQlResponse {

        private final String document;
        private final int status;
        private final JsonPath json;

        GraphQlResponse(String document, int status, JsonPath json) {
            this.document = document;
            this.status = status;
            this.json = json;
        }

        public int status() {
            return status;
        }

        public GraphQlResponse assertNoErrors() {
            List<Map<String, Object>> errors = errors();
            if (!errors.isEmpty()) {
                throw new AssertionError("a operação devolveu erros: " + errors + "\ndocumento: " + document);
            }
            return this;
        }

        public <T> T get(String path) {
            return json.get("data." + path);
        }

        public String string(String path) {
            return json.getString("data." + path);
        }

        public int integer(String path) {
            return json.getInt("data." + path);
        }

        public boolean bool(String path) {
            return json.getBoolean("data." + path);
        }

        public List<?> list(String path) {
            return json.getList("data." + path);
        }

        public boolean isNull(String path) {
            return json.get("data." + path) == null;
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> errors() {
            List<Map<String, Object>> errors = json.getList("errors");
            return errors == null ? List.of() : errors;
        }

        /** O {@code extensions.code} do primeiro erro — a classificação que {@code GraphQlErrors} deu. */
        @SuppressWarnings("unchecked")
        public String errorCode() {
            List<Map<String, Object>> errors = errors();
            if (errors.isEmpty()) {
                throw new AssertionError("esperado um erro, veio sucesso\ndocumento: " + document);
            }
            Map<String, Object> extensions = (Map<String, Object>) errors.getFirst().get("extensions");
            return extensions == null ? null : String.valueOf(extensions.get("code"));
        }

        public String errorMessage() {
            List<Map<String, Object>> errors = errors();
            if (errors.isEmpty()) {
                throw new AssertionError("esperado um erro, veio sucesso\ndocumento: " + document);
            }
            return String.valueOf(errors.getFirst().get("message"));
        }
    }
}
