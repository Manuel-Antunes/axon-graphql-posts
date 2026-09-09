package dev.manuelantunes.axonposts.e2e;

import dev.manuelantunes.axonposts.application.post.command.CreatePostCommand.CreatePost;
import dev.manuelantunes.axonposts.application.user.command.RegisterUserCommand.RegisterUser;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.user.exception.NotAnAuthorException;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.exceptions.DataIntegrityTranslator;
import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import dev.manuelantunes.axonposts.support.KeycloakContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * As restrições do banco como <b>garantia</b>, e a tradução delas em erro de domínio.
 *
 * <h2>O que mudou, e por quê</h2>
 * O {@code CreatePostCommand} consultava o agregado {@code User} para confirmar que o {@code authorId}
 * existia e era de um autor. A consulta saiu por três razões, e a terceira é a que decide: ela
 * <b>não garantia nada</b>. Entre o SELECT e o INSERT o autor pode ser apagado — a janela existe e a
 * checagem só tranquilizava. A chave estrangeira não tem janela.
 * <p>
 * O que faltava para poder confiar nela era a tradução: um erro de integridade não pode chegar ao cliente
 * como {@code INTERNAL_ERROR}. Estes testes garantem as duas metades — que a restrição recusa, e que a
 * recusa vira o mesmo erro tipado que a checagem produzia.
 */
class DataIntegrityE2ETest extends AbstractGraphQlE2ETest {

    @Autowired
    private CommandGateway commandGateway;

    /**
     * Um produtor que não seja o controller GraphQL — outro serviço, um consumidor de mensagem, um
     * script — pode mandar qualquer {@code authorId}. É esse o caminho que a chave estrangeira protege, e
     * é por ele que o teste entra: pelo controller não dá, porque lá o autor sai do token.
     */
    @Test
    void aPostWithAnUnknownAuthorIsRefusedByTheForeignKey() {
        assertThatThrownBy(() -> commandGateway.sendAndWait(
                new CreatePost(PostId.newId(), "De um fantasma", "conteúdo", UserId.newId())))
                .rootCause()
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);

        assertThat(jdbc.queryForObject("select count(*) from posts", Integer.class))
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
        commandGateway.sendAndWait(new RegisterUser(readerId, "leitor-teste@example.com", "Leitor", false, null, null));

        assertThatThrownBy(() -> commandGateway.sendAndWait(
                new CreatePost(PostId.newId(), "Escrito por leitor", "conteúdo", readerId)))
                .rootCause()
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);

        assertThat(jdbc.queryForObject("select count(*) from posts", Integer.class)).isZero();
    }

    @Test
    void theRefusalIsTranslatedIntoADomainError() {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> commandGateway.sendAndWait(
                new CreatePost(PostId.newId(), "De um fantasma", "conteúdo", UserId.newId())));

        // é o que o AppGraphQlExceptionHandler faz antes de classificar: sem isto, o cliente veria
        // INTERNAL_ERROR com um stack trace de JDBC
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
     * nada em tempo de compilação — a violação simplesmente voltaria a ser {@code INTERNAL_ERROR}, em
     * silêncio, e só apareceria para um usuário.
     * <p>
     * Este teste pergunta ao Postgres se cada nome existe de fato. É o que torna o acoplamento seguro em
     * vez de frágil — e é também a razão de o {@code V1} ter sido curado à mão: não dá para ancorar
     * tradução de erro em {@code FKnjuop33mo69pd79ctplkck40n}.
     */
    @Test
    void everyConstraintTheTranslatorKnowsActuallyExists() {
        // constraints e índices: um índice único parcial (uk_users_email_active) não vira constraint,
        // mas o Hibernate reporta o nome dele igual numa violação
        List<String> inDatabase = jdbc.queryForList("""
                select conname as name from pg_constraint
                  where connamespace = 'public'::regnamespace
                union
                select indexname as name from pg_indexes where schemaname = 'public'
                """, String.class);

        Set<String> known = DataIntegrityTranslator.knownConstraints();
        assertThat(known).isNotEmpty();
        assertThat(inDatabase)
                .as("nomes que o tradutor conhece mas o schema não tem: %s", known)
                .containsAll(known);
    }

    @Test
    void theRegularPathStillWorks() {
        // a FK não atrapalha quem tem direito: o autor do token escreve normalmente
        createPost(asAuthor(), "Do autor de verdade", "conteúdo");

        anonymous.document(
                //language=GraphQL
                "{ posts(first: 5) { edges { node { title } } } }").execute()
                .path("posts.edges").entityList(Object.class).hasSize(1);
        assertThat(KeycloakContainerConfig.AUTHOR_USERNAME).isNotBlank();
    }
}
