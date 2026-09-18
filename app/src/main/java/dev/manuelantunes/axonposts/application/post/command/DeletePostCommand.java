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
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;

import static dev.manuelantunes.axonposts.application.shared.AppendingDomainEventPublisher.appendingTo;

/**
 * O command <b>DeletePost</b>: exclusão lógica.
 * <p>
 * O domínio recusa apagar o que já está apagado ({@code AlreadyDeletedException}, vinda da guarda do
 * mixin {@code SoftDeletable}), dispara o {@code PostDeletedEvent} e devolve o Post; o handler salva.
 * <p>
 * O {@code save} aqui é um {@code merge} comum: no momento em que ele roda a linha ainda está visível
 * (o {@code deleted_at} só existe no objeto em memória), então o UPDATE a encontra e a esconde. É o
 * caminho de volta que precisa de tratamento especial — ver {@link RestorePostCommand}.
 */
@ApplicationScoped
public class DeletePostCommand {

    /**
     * A mensagem: apagar um post. O id, e quem está pedindo — ver {@code Post.assertWrittenBy}.
     * <p>
     * <h3>A tradução id → entidade acontece aqui, na fronteira</h3>
     * A <b>mensagem</b> carrega {@code UserId}, porque mensagem carrega dado: ela é serializada, atravessa
     * processo e não pode depender de uma classe de domínio. O <b>domínio</b> recebe {@code Author},
     * porque um método de domínio fala do que existe no domínio — e porque o tipo já exclui um leitor
     * antes de o corpo do método rodar.
     * <p>
     * {@code Author.reference(id)} é a costura entre os dois: uma entidade com identidade e nada mais, que
     * é exatamente o que o {@code Post} precisa saber sobre o autor. Não custa consulta, e é a mesma
     * instância que o replay monta a partir do evento.
     */
    @Command(namespace = "posts", name = "DeletePost", version = "1.0.0")
    public record DeletePost(@TargetEntityId PostId postId, UserId actingAuthor) {
    }

    private final Clock clock;
    private final PostRepository posts;

    public DeletePostCommand(Clock clock, PostRepository posts) {
        this.clock = clock;
        this.posts = posts;
    }

    @CommandHandler
    public void handle(DeletePost command,
                       @InjectEntity Post post,
                       EventAppender eventAppender) {
        Post deleted = post.delete(
                Author.reference(command.actingAuthor()), clock.instant(), appendingTo(eventAppender));

        posts.save(deleted);
    }
}
