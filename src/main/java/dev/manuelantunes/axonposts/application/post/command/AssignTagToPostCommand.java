package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.post.vo.TagRef;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.springframework.stereotype.Component;

import java.time.Clock;

import static dev.manuelantunes.axonposts.application.shared.AppendingDomainEventPublisher.appendingTo;

/**
 * O command <b>AssignTagToPost</b>: a mensagem {@link AssignTagToPost} e o que acontece quando ela chega.
 * <p>
 * O Axon reidrata o {@link Post} do stream dele; o domínio decide se a tag pode entrar e dispara o
 * {@code PostUpdatedEvent} com a lista de tags resultante; o {@link #handle} salva o estado devolvido.
 * <p>
 * Note que a Tag <b>não</b> é carregada aqui: o post guarda uma cópia do id e do nome, e validar que a
 * tag existe é responsabilidade de quem despacha o command — é o preço, e a vantagem, de os dois serem
 * agregados independentes.
 */
@Component
public class AssignTagToPostCommand {

    /**
     * A mensagem: assinalar uma tag já existente a um post.
     * <p>
     * Carrega {@code tagId} e {@code tagName} como texto, e não um {@code TagId}: a mensagem atravessa a
     * fronteira entre dois agregados, e o que atravessa é dado. O nome vem junto porque o Post guarda
     * uma cópia dele ({@code TagRef}) — assim exibir um post não obriga a carregar o agregado Tag.
     */
    @Command(namespace = "posts", name = "AssignTagToPost", version = "1.0.0")
    public record AssignTagToPost(
            @TargetEntityId PostId postId,
            String tagId,
            String tagName
    ) {
    }

    private final Clock clock;
    private final PostRepository posts;

    public AssignTagToPostCommand(Clock clock, PostRepository posts) {
        this.clock = clock;
        this.posts = posts;
    }

    @CommandHandler
    public void handle(AssignTagToPost command,
                       @InjectEntity Post post,
                       EventAppender eventAppender) {
        Post tagged = post.assignTag(
                TagRef.of(command.tagId(), command.tagName()),
                clock.instant(),
                appendingTo(eventAppender)
        );

        posts.save(tagged);
    }
}
