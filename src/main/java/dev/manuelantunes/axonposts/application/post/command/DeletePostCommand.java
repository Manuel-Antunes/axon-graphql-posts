package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.springframework.stereotype.Component;

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
@Component
public class DeletePostCommand {

    /** A mensagem: apagar um post. Só o id — não há o que escolher. */
    @Command(namespace = "posts", name = "DeletePost", version = "1.0.0")
    public record DeletePost(@TargetEntityId PostId postId) {
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
        Post deleted = post.delete(clock.instant(), appendingTo(eventAppender));

        posts.save(deleted);
    }
}
