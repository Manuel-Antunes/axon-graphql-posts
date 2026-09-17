package dev.manuelantunes.axonposts.e2e;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.application.post.command.CreatePostCommand.CreatePost;
import dev.manuelantunes.axonposts.application.user.command.RegisterUserCommand.RegisterUser;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.user.exception.NotAnAuthorException;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.interfaces.graphql.error.DataIntegrityTranslator;
import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static dev.manuelantunes.axonposts.support.PostCommandFixtures.hasCause;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * As restrições do banco como <b>garantia</b>, e a tradução delas em erro de domínio.
 *
 * <h2>Por que a chave estrangeira, e não uma consulta</h2>
 * O {@code CreatePostCommand} consultava o agregado {@code User} para confirmar que o {@code authorId}
 * existia e era de um autor. A consulta saiu por três razões, e a terceira é a que decide: ela
 * <b>não garantia nada</b>. Entre o SELECT e o INSERT o autor pode ser apagado — a janela existe e a
 * checagem só tranquilizava. A chave estrangeira não tem janela.
 * <p>
 * O que faltava para poder confiar nela era a tradução: um erro de integridade não pode chegar ao cliente
 * como erro interno. Estes testes garantem as duas metades — que a restrição recusa, e que a recusa vira
 * o mesmo erro tipado que a checagem produzia.
 *
 * <h2>Percorrer a cadeia, e não olhar a raiz</h2>
 * A versão Spring afirmava {@code .rootCause().isInstanceOf(EntityNotFoundException.class)}, porque lá a
 * exceção chegava embrulhada em pelo menos uma camada. No Quarkus o {@code sendAndWait} entrega a
 * {@code EntityNotFoundException} <b>crua</b>, sem causa — e {@code rootCause()} do AssertJ exige que haja
 * uma. Afirmar sobre a <i>cadeia</i> ({@code hasCause}) é o que vale nos dois casos, e é a mesma coisa que
 * {@code GraphQlErrors} e {@code DataIntegrityTranslator} fazem em produção: nenhum dos dois assume
 * profundidade de embrulho.
 */
@QuarkusTest
class DataIntegrityE2ETest extends AbstractGraphQlE2ETest {

    @Inject
    CommandGateway commandGateway;

    /**
     * Um produtor que não seja o resolver GraphQL — outro serviço, um consumidor de mensagem, um
     * script — pode mandar qualquer {@code authorId}. É esse o caminho que a chave estrangeira protege, e
     * é por ele que o teste entra: pelo resolver não dá, porque lá o autor sai do token.
     */
    @Test
    void aPostWithAnUnknownAuthorIsRefusedByTheForeignKey() {
        assertThatThrownBy(() -> commandGateway.sendAndWait(
                new CreatePost(PostId.newId(), "De um fantasma", "conteúdo", UserId.newId())))
                .satisfies(thrown -> assertThat(hasCause(thrown, jakarta.persistence.EntityNotFoundException.class))
                        .as("a recusa do Hibernate precisa estar na cadeia, embrulhada ou não").isTrue());

        assertThat(count("select count(*) from posts"))
                .as("evento e linha commitam juntos: nenhum dos dois sobrou").isZero();
    }

    /**
     * O caso que a consulta removida cobria: um usuário que existe, mas é leitor.
     * <p>
     * A FK aponta para {@code authors} e não para {@code users}, então ela recusa exatamente igual — sem
     * precisar que a aplicação saiba a diferença entre "não existe" e "não é autor".
     */
    @Test
    void aPostWrittenByAReaderIsRefusedByTheSameForeignKey() {
        UserId readerId = UserId.newId();
        commandGateway.sendAndWait(
                new RegisterUser(readerId, "leitor-teste@example.com", "Leitor", false, null, null));

        assertThatThrownBy(() -> commandGateway.sendAndWait(
                new CreatePost(PostId.newId(), "Escrito por leitor", "conteúdo", readerId)))
                .satisfies(thrown -> assertThat(hasCause(thrown, jakarta.persistence.EntityNotFoundException.class))
                        .isTrue());

        assertThat(count("select count(*) from posts")).isZero();
    }

    @Test
    void theRefusalIsTranslatedIntoADomainError() {
        Throwable thrown = catchThrowable(() -> commandGateway.sendAndWait(
                new CreatePost(PostId.newId(), "De um fantasma", "conteúdo", UserId.newId())));

        // é o que GraphQlErrors faz antes de classificar: sem isto, o cliente veria "System Error" com um
        // stack trace de JDBC
        assertThat(DataIntegrityTranslator.translate(thrown))
                .hasValueSatisfying(translated -> assertThat(translated)
                        .isInstanceOf(NotAnAuthorException.class)
                        // vaga de propósito: distinguir "não existe" de "não é autor" seria um oráculo
                        .hasMessageNotContainingAny("não existe:", "id"));
    }

    /**
     * O guarda do acoplamento.
     *
     * <h3>Por que ele precisa existir</h3>
     * O tradutor casa por <b>nome de constraint</b>, então ele depende de a migration e o mapa dele
     * concordarem. Renomear {@code fk_posts_author} no {@code V1} sem tocar no tradutor não quebraria
     * nada em tempo de compilação — a violação simplesmente voltaria a ser erro interno, em silêncio, e só
     * apareceria para um usuário.
     * <p>
     * Este teste pergunta ao Postgres se cada nome existe de fato. É o que torna o acoplamento seguro em
     * vez de frágil — e é também a razão de o {@code V1} ter sido curado à mão: não dá para ancorar
     * tradução de erro em {@code FKnjuop33mo69pd79ctplkck40n}.
     */
    @Test
    void everyConstraintTheTranslatorKnowsActuallyExists() {
        // constraints e índices: um índice único parcial (uk_users_email_active) não vira constraint,
        // mas o Hibernate reporta o nome dele igual numa violação
        List<String> inDatabase = names("""
                select conname as name from pg_constraint
                  where connamespace = 'public'::regnamespace
                union
                select indexname as name from pg_indexes where schemaname = 'public'
                """);

        Set<String> known = DataIntegrityTranslator.knownConstraints();
        assertThat(known).isNotEmpty();
        assertThat(inDatabase)
                .as("nomes que o tradutor conhece mas o schema não tem: %s", known)
                .containsAll(known);
    }

    @Test
    void theRegularPathStillWorks() {
        // a FK não atrapalha quem tem direito: o autor do token escreve normalmente
        asAuthor().createPost("Do autor de verdade", "conteúdo");

        assertThat(anonymous.execute("{ posts(first: 5) { edges { node { title } } } }")
                .list("posts.edges")).hasSize(1);
    }

    private List<String> names(String sql) {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("falha ao listar constraints", e);
        }
        return names;
    }
}
