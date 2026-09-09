package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.springframework.stereotype.Component;

import java.time.Clock;

import static dev.manuelantunes.axonposts.application.shared.AppendingDomainEventPublisher.appendingTo;

/**
 * O command <b>RestorePost</b>: desfaz a exclusão lógica.
 *
 * <h2>Por que o event sourcing salva este caso</h2>
 * Um post apagado é invisível para o JPA — o {@code @SQLRestriction} o esconde de toda consulta. Num
 * sistema comum, restaurar seria um problema de galinha e ovo: para restaurar é preciso carregar, e para
 * carregar é preciso não estar apagado.
 * <p>
 * Aqui não é, porque o {@code @InjectEntity Post} <b>não vem do banco</b>: o Axon o reidrata do stream de
 * eventos, que nenhuma cláusula SQL filtra. A decisão acontece normalmente sobre um agregado completo.
 *
 * <h2>As duas escritas, e a ordem entre elas</h2>
 * <ol>
 *   <li>{@code posts.restore(id)} — UPDATE nativo que zera o {@code deleted_at} e faz a linha voltar a
 *       existir para o JPA;</li>
 *   <li>{@code posts.save(post)} — agora o {@code merge} acha a linha e grava versão e {@code updatedAt}.</li>
 * </ol>
 * Invertida, a ordem não funciona: o merge não encontraria a linha escondida, concluiria que a entidade é
 * nova e tentaria um INSERT com uma chave primária que já existe. As duas rodam na transação do
 * {@code ProcessingContext}, então ou as duas valem, ou nenhuma.
 */
@Component
public class RestorePostCommand {

    /**
     * A mensagem: restaurar um post apagado.
     * <p>
     * A checagem de dono funciona aqui mesmo com o post invisível para o JPA, porque o agregado vem do
     * stream: o {@code author} está reconstituído do {@code PostCreatedEvent}, e o domínio compara sem
     * precisar do banco.
     */
    @Command(namespace = "posts", name = "RestorePost", version = "1.0.0")
    public record RestorePost(@TargetEntityId PostId postId, UserId actingAuthor) {
    }

    private final Clock clock;
    private final PostRepository posts;

    public RestorePostCommand(Clock clock, PostRepository posts) {
        this.clock = clock;
        this.posts = posts;
    }

    @CommandHandler
    public void handle(RestorePost command,
                       @InjectEntity Post post,
                       EventAppender eventAppender) {
        Post restored = post.restore(
                Author.reference(command.actingAuthor()), clock.instant(), appendingTo(eventAppender));

        posts.restore(restored.id());
        posts.save(restored);
    }
}
