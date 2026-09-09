package dev.manuelantunes.axonposts.infrastructure.persistence.sqlite;

import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A exclusão lógica do {@code User} contra o <b>banco de verdade</b>, e não contra um duplo em memória.
 *
 * <h2>Por que este teste precisa existir</h2>
 * Os outros testes de soft delete rodam sobre objetos: provam as regras do mixin, não o que o Hibernate
 * emite. E o ponto mais frágil desta feature está exatamente no SQL — numa herança {@code JOINED} o
 * Hibernate emite <b>um DELETE por tabela</b>, então sem o {@code @SQLDelete} do {@code Author} a linha
 * de {@code authors} seria removida de verdade enquanto a de {@code users} só era marcada. O autor
 * voltaria de um restore como se fosse um leitor, sem bio.
 * <p>
 * É o tipo de bug que nenhum teste de unidade pega e que só aparece no ciclo apagar → restaurar inteiro.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaUserRepository.class, JpaPostRepository.class})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:target/soft-delete-test.db",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class UserSoftDeleteJpaTest {

    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    @Autowired
    private UserRepository users;

    @Autowired
    private SpringDataUserRepository springData;

    @Autowired
    private EntityManager entityManager;

    private UserId authorId;

    @BeforeEach
    void setUp() {
        authorId = UserId.newId();
        users.save(Author.register(authorId, "autor@example.com", "Autor", "hash", "bio do autor", NOW));
        flushAndClear();
    }

    /** Sem isto, as asserções leriam o cache de primeiro nível em vez do banco. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /** Conta a linha sem passar pelo mapeamento — é o único jeito de enxergar o que o filtro esconde. */
    private long rawCount(String table, String where) {
        return ((Number) entityManager
                .createNativeQuery("select count(*) from " + table + " where " + where)
                .getSingleResult()).longValue();
    }

    @Test
    void deleteThroughTheJpaRepositoryMarksInsteadOfRemoving() {
        springData.delete(users.findById(authorId).orElseThrow());
        flushAndClear();

        // some das consultas...
        assertThat(users.findById(authorId)).isEmpty();
        assertThat(users.findByEmail(Email.of("autor@example.com"))).isEmpty();

        // ...mas a linha continua lá, marcada
        assertThat(rawCount("users", "id = '" + authorId.value() + "' and deleted_at is not null")).isEqualTo(1);
    }

    @Test
    void theAuthorRowSurvivesTheDelete() {
        springData.delete(users.findById(authorId).orElseThrow());
        flushAndClear();

        // o @SQLDelete do Author é o que segura isto: sem ele, o DELETE da tabela filha passaria
        assertThat(rawCount("authors", "id = '" + authorId.value() + "'")).isEqualTo(1);
    }

    @Test
    void restoreBringsBackTheAuthorWithTheSubclassIntact() {
        springData.delete(users.findById(authorId).orElseThrow());
        flushAndClear();

        users.restore(authorId);
        flushAndClear();

        assertThat(users.findById(authorId)).hasValueSatisfying(user -> {
            // voltou como Author, não como User: a linha filha nunca foi embora
            assertThat(user).isInstanceOf(Author.class);
            assertThat(((Author) user).bio()).isEqualTo("bio do autor");
            assertThat(user.isDeleted()).isFalse();
            assertThat(user.roles()).containsExactlyInAnyOrder(
                    dev.manuelantunes.axonposts.domain.user.Role.USER,
                    dev.manuelantunes.axonposts.domain.user.Role.AUTHOR);
        });
    }

    @Test
    void markingThroughTheMixinAndSavingHasTheSameEffect() {
        // o outro caminho: o domínio marca, o save persiste. Não passa por @SQLDelete nenhum
        User user = users.findById(authorId).orElseThrow();
        user.delete(NOW);
        users.save(user);
        flushAndClear();

        assertThat(users.findById(authorId)).isEmpty();
        assertThat(rawCount("users", "id = '" + authorId.value() + "' and deleted_at is not null")).isEqualTo(1);
    }
}
