package dev.manuelantunes.axonposts.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

/**
 * Base dos testes ponta a ponta: aplicação de verdade, Postgres de verdade, Keycloak de verdade, token
 * de verdade.
 *
 * <h2>O que "ponta a ponta" significa aqui</h2>
 * A requisição entra por HTTP com um {@code Authorization: Bearer} emitido pelo realm, atravessa o filtro
 * de segurança, o {@code @PreAuthorize}, o provisionamento, o command bus do Axon, o domínio, o JPA e a
 * projeção, e volta como JSON do GraphQL. Nada é dublado. É a diferença para os testes de command, que
 * montam só o handler e um repositório em memória.
 * <p>
 * Estes testes substituem o que antes era conferido à mão com {@code curl}: o que se verificava numa
 * sessão de terminal agora falha o build quando quebra.
 *
 * <h2>Um contexto para todas as subclasses</h2>
 * Todas herdam a mesma anotação e as mesmas propriedades, então o Spring <b>reaproveita o contexto</b>
 * entre elas — a aplicação sobe uma vez para a suíte inteira, como os containers. Uma subclasse que
 * acrescente {@code @TestPropertySource} ganharia um contexto próprio e pagaria a subida de novo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractGraphQlE2ETest {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        Containers.registerProperties(registry);
    }

    @Autowired
    protected WebTestClient webTestClient;

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * O {@code WebTestClient} injetado aponta para a raiz do servidor, e o {@code HttpGraphQlTester} não
     * acrescenta caminho nenhum — ele posta na baseUrl que recebe. Sem apontar para {@code /graphql},
     * toda requisição volta 404.
     */
    @LocalServerPort
    protected int port;

    protected HttpGraphQlTester anonymous;

    /**
     * Cada teste começa com o banco vazio.
     * <p>
     * As classes compartilham contexto e banco — subir um por método custaria dezenas de segundos —, e o
     * preço disso é que a ordem de execução vazaria de um teste para o outro. Um que afirme "este usuário
     * ainda não existe" passa sozinho e falha depois de outro tê-lo provisionado.
     * <p>
     * O {@code CASCADE} resolve as chaves estrangeiras entre posts, contas e usuários sem precisar acertar
     * a ordem do truncate à mão.
     * <p>
     * O <b>event store fica</b>: ele é em memória e vive com o contexto. Não incomoda porque todo id é um
     * UUID novo — nenhum teste reidrata o stream de outro.
     */
    @BeforeEach
    void resetDatabaseAndTester() {
        jdbc.execute("truncate table post_tags, posts, tags, accounts, authors, users cascade");
        anonymous = HttpGraphQlTester.create(webTestClient.mutate()
                .baseUrl("http://localhost:" + port + "/graphql")
                .responseTimeout(Duration.ofSeconds(30))
                .build());
    }

    /** Um cliente GraphQL autenticado como o usuário do realm, com token recém-emitido. */
    protected HttpGraphQlTester as(String username) {
        String token = KeycloakTokens.accessToken(
                Containers.KEYCLOAK, username, KeycloakContainerConfig.PASSWORD);
        return anonymous.mutate()
                .headers(headers -> headers.setBearerAuth(token))
                .build();
    }

    /** Atalho para o autor semeado no realm — quem pode escrever. */
    protected HttpGraphQlTester asAuthor() {
        return as(KeycloakContainerConfig.AUTHOR_USERNAME);
    }

    /** Atalho para o leitor semeado no realm — quem não pode. */
    protected HttpGraphQlTester asReader() {
        return as(KeycloakContainerConfig.READER_USERNAME);
    }

    /** Cria um post pelo caminho normal (mutation autenticada) e devolve o id. */
    protected String createPost(HttpGraphQlTester tester, String title, String content) {
        return tester.document("""
                        mutation Criar($t: String!, $c: String!) {
                          createPost(input: {title: $t, content: $c}) { id }
                        }""")
                .variable("t", title)
                .variable("c", content)
                .execute()
                .path("createPost.id").entity(String.class).get();
    }
}
