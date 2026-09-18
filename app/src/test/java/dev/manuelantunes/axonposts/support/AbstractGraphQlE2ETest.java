package dev.manuelantunes.axonposts.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;

import jakarta.inject.Inject;

/**
 * Base dos testes ponta a ponta: aplicação de verdade, Postgres de verdade, Keycloak de verdade, token
 * de verdade.
 *
 * <h2>O que "ponta a ponta" significa aqui</h2>
 * A requisição entra por HTTP com um {@code Authorization: Bearer} emitido pelo realm, atravessa o
 * {@code quarkus-oidc}, o {@code @RolesAllowed}, o provisionamento, o command bus do Axon, o domínio, o
 * JPA e a projeção, e volta como JSON do GraphQL. Nada é dublado. É a diferença para os testes de
 * command, que montam só o handler e um repositório em memória.
 *
 * <h2>Uma aplicação para todas as subclasses</h2>
 * {@code @QuarkusTest} sobe a aplicação <b>uma vez</b> por perfil de teste e a compartilha com todas as
 * classes anotadas. É a mesma economia que o Spring faz reaproveitando o contexto — com a diferença de
 * que aqui os containers (Postgres e Keycloak) também vêm do Dev Services, sem uma linha de
 * Testcontainers escrita à mão. As três classes de infraestrutura do projeto Spring
 * ({@code Containers}, {@code KeycloakContainerConfig}, {@code KeycloakTokens}) viraram
 * {@link Realm} e {@link GraphQl}.
 * <p>
 * Uma subclasse que acrescente {@code @TestProfile} ganha uma aplicação própria e a suíte paga outra
 * subida — é o equivalente exato do {@code @TestPropertySource} no Spring.
 *
 * <h2>Por que o {@code @QuarkusTest} fica nas subclasses, e não aqui</h2>
 * Porque é a anotação que faz o Quarkus registrar a classe de teste como <b>bean</b>, em tempo de build,
 * para que os {@code @Inject} dela sejam satisfeitos. O registro olha para a classe anotada, não para a
 * hierarquia: com a anotação só nesta base, a extensão do JUnit ativa (ela é herdada) mas cada subclasse
 * falha com "No bean found for required type". Anotar cada concreta é uma linha, e é onde o Quarkus
 * espera encontrá-la.
 */
public abstract class AbstractGraphQlE2ETest {

    @Inject
    protected DataSource dataSource;

    protected GraphQl anonymous;

    /**
     * Cada teste começa com o banco vazio.
     * <p>
     * As classes compartilham aplicação e banco — subir um por método custaria dezenas de segundos —, e o
     * preço disso é que a ordem de execução vazaria de um teste para o outro. Um que afirme "este usuário
     * ainda não existe" passa sozinho e falha depois de outro tê-lo provisionado.
     * <p>
     * O {@code CASCADE} resolve as chaves estrangeiras entre posts, contas e usuários sem precisar acertar
     * a ordem do truncate à mão.
     * <p>
     * O <b>event store fica</b>: ele é em memória e vive com a aplicação. Não incomoda porque todo id é um
     * UUID novo — nenhum teste reidrata o stream de outro.
     */
    @BeforeEach
    void resetDatabase() {
        execute("truncate table post_tags, posts, tags, accounts, authors, users cascade");
        anonymous = GraphQl.anonymous();
    }

    protected GraphQl asAuthor() {
        return GraphQl.asAuthor();
    }

    protected GraphQl asReader() {
        return GraphQl.asReader();
    }

    protected GraphQl as(String username) {
        return GraphQl.as(username);
    }

    /** O {@code jdbc.queryForObject(..., Integer.class)} do projeto Spring, em JDBC puro. */
    protected int count(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("falha ao consultar: " + sql, e);
        }
    }

    protected void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("falha ao executar: " + sql, e);
        }
    }
}
