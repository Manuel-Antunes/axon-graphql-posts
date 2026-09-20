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

@QuarkusTest
class DataIntegrityE2ETest extends AbstractGraphQlE2ETest {
    @Inject
    CommandGateway commandGateway;

    @Test
    void aPostWithAnUnknownAuthorIsRefusedByTheForeignKey() {
        assertThatThrownBy(() -> commandGateway.sendAndWait(
                new CreatePost(PostId.newId(), "De um fantasma", "conteúdo", UserId.newId(), List.of())))
                .satisfies(thrown -> assertThat(hasCause(thrown, jakarta.persistence.EntityNotFoundException.class))
                        .as("a recusa do Hibernate precisa estar na cadeia, embrulhada ou não").isTrue());

        assertThat(count("select count(*) from posts"))
                .as("evento e linha commitam juntos: nenhum dos dois sobrou").isZero();
    }

    @Test
    void aPostWrittenByAReaderIsRefusedByTheSameForeignKey() {
        UserId readerId = UserId.newId();
        commandGateway.sendAndWait(
                new RegisterUser(readerId, "leitor-teste@example.com", "Leitor", false, null, null));

        assertThatThrownBy(() -> commandGateway.sendAndWait(
                new CreatePost(PostId.newId(), "Escrito por leitor", "conteúdo", readerId, List.of())))
                .satisfies(thrown -> assertThat(hasCause(thrown, jakarta.persistence.EntityNotFoundException.class))
                        .isTrue());

        assertThat(count("select count(*) from posts")).isZero();
    }

    @Test
    void theRefusalIsTranslatedIntoADomainError() {
        Throwable thrown = catchThrowable(() -> commandGateway.sendAndWait(
                new CreatePost(PostId.newId(), "De um fantasma", "conteúdo", UserId.newId(), List.of())));

        assertThat(DataIntegrityTranslator.translate(thrown))
                .hasValueSatisfying(translated -> assertThat(translated)
                        .isInstanceOf(NotAnAuthorException.class)
                        .hasMessageNotContainingAny("não existe:", "id"));
    }

    @Test
    void everyConstraintTheTranslatorKnowsActuallyExists() {
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
