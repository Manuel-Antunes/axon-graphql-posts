package dev.manuelantunes.axonposts.support;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.BeforeEach;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;

public abstract class AbstractGraphQlE2ETest {
    @Inject
    protected DataSource dataSource;

    @Inject
    protected EntityManagerFactory entityManagerFactory;

    protected GraphQl anonymous;

    @BeforeEach
    void beforeEach() {
        resetDatabase();
        anonymous = GraphQl.anonymous();
    }

    protected void resetDatabase() {
        execute("truncate table post_tags, posts, tags, accounts, authors, users, "
                + "aggregateevententry, tokenentry, axon_message_inbox cascade");
        execute("insert into tags (id, name, created_at) values "
                + "('fa65e148-3f7c-3860-a765-a70f54983048', 'Untagged', '2026-01-01T00:00:00Z') "
                + "on conflict do nothing");
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

    protected String createTaggedPost(GraphQl author, String title, String content) {
        String id = author.createPost(title, content);
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(
                anonymous.execute("query Etiquetado($id: ID!) { post(id: $id) { version } }", "id", id)
                        .integer("post.version"))
                .as("a primeira tag chega e leva o post à versão 2 (completo)")
                .isEqualTo(2));
        return id;
    }

    private static final Duration QUIET_SAMPLE = Duration.ofMillis(150);

    protected Statistics statisticsOfAQuietDatabase() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        AtomicLong previous = new AtomicLong(-1);
        await().atMost(Duration.ofSeconds(20))
                .pollDelay(QUIET_SAMPLE)
                .pollInterval(QUIET_SAMPLE)
                .until(() -> {
                    long now = statistics.getPrepareStatementCount();
                    return previous.getAndSet(now) == now;
                });

        statistics.clear();
        return statistics;
    }

    protected long cheapestStatementCount(Runnable query) {
        long cheapest = Long.MAX_VALUE;
        for (int attempt = 0; attempt < STATEMENT_SAMPLES; attempt++) {
            Statistics statistics = statisticsOfAQuietDatabase();
            query.run();
            cheapest = Math.min(cheapest, statistics.getPrepareStatementCount());
        }
        return cheapest;
    }

    private static final int STATEMENT_SAMPLES = 3;
}
