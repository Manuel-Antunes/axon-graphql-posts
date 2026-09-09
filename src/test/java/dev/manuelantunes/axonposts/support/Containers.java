package dev.manuelantunes.axonposts.support;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Os containers da suíte de integração: <b>um</b> Postgres e <b>um</b> Keycloak, para todas as classes.
 *
 * <h2>Por que estáticos e não beans</h2>
 * Duas razões, e a segunda é a que obriga:
 * <ul>
 *   <li><b>custo</b> — o Keycloak leva ~15s para ficar pronto. Um por classe de teste multiplicaria isso
 *       por classe; estáticos, sobem uma vez por JVM e todas as classes reaproveitam;</li>
 *   <li><b>ordem</b> — o {@code issuer-uri} é resolvido quando o Spring cria o {@code ReactiveJwtDecoder},
 *       e nesse momento o Boot já vai buscar o {@code openid-configuration}. O Keycloak precisa estar de
 *       pé <b>antes</b> do contexto, e um {@code @Bean} não pode garantir isso: ele é criado <i>dentro</i>
 *       do contexto. Um bloco {@code static} roda no carregamento da classe, que acontece antes.</li>
 * </ul>
 *
 * <h2>{@code Startables.deepStart}</h2>
 * Sobe os dois em <b>paralelo</b>. Não há dependência entre eles — o Keycloak dos testes usa o banco H2
 * embutido dele, não este Postgres — então serializar só somaria os tempos.
 * <p>
 * Diferente do {@code docker-compose.yml}, onde o Keycloak usa o Postgres de propósito: lá o objetivo é
 * demonstrar os dois inquilinos num servidor só. Aqui o objetivo é o teste rodar rápido, e o schema do
 * Keycloak não é o que está sendo verificado.
 *
 * <h2>Quem para</h2>
 * Ninguém, explicitamente. O Ryuk (container sentinela do Testcontainers) derruba tudo quando a JVM
 * morre. Um {@code @AfterAll} pararia os containers entre classes e quebraria o compartilhamento.
 */
public final class Containers {

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("axonposts")
                    .withUsername("axonposts")
                    .withPassword("axonposts");

    public static final KeycloakContainer KEYCLOAK = KeycloakContainerConfig.container();

    static {
        Startables.deepStart(POSTGRES, KEYCLOAK).join();
    }

    private Containers() {
    }

    /**
     * Liga os dois containers às propriedades da aplicação.
     * <p>
     * O datasource poderia vir de {@code @ServiceConnection}, mas então o Postgres seria um bean e
     * deixaria de ser compartilhado entre contextos diferentes — o {@code @DataJpaTest} e o
     * {@code @SpringBootTest} sobem contextos distintos e precisam do <b>mesmo</b> banco para que uma
     * única imagem sirva à suíte inteira.
     */
    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> KeycloakContainerConfig.issuerUri(KEYCLOAK));
        // o schema vem do Flyway, como em produção — os testes de integração exercitam as migrations de
        // verdade, e não uma segunda definição de schema que poderia divergir delas.
        // Cada teste limpa as LINHAS (truncate), nunca o schema.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // ligado aqui, e não num @TestPropertySource de uma subclasse, porque qualquer propriedade a mais
        // numa subclasse lhe daria um contexto próprio — e a suíte pagaria outra subida da aplicação.
        // É o que permite ao BatchLoadingE2ETest contar statements sem custar um contexto extra.
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    /** Só o banco, para os testes que não precisam de identidade ({@code @DataJpaTest}). */
    public static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
