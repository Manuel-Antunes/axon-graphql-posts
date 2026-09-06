package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.TagRef;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

import java.time.Clock;

import static dev.manuelantunes.axonposts.application.shared.AppendingDomainEventPublisher.appendingTo;

/**
 * Handler de <b>um</b> command: {@link AssignTagToPostCommand}.
 * <p>
 * O Axon reidrata o {@link Post} do stream dele; o domínio decide se a tag pode entrar e dispara o
 * {@code PostUpdatedEvent} com a lista de tags resultante; o handler salva o estado devolvido.
 * <p>
 * Note que a Tag <b>não</b> é carregada aqui: o post guarda uma cópia do id e do nome, e validar que a
 * tag existe é responsabilidade de quem despacha o command — é o preço, e a vantagem, de os dois serem
 * agregados independentes.
 */
@Component
public class AssignTagToPostCommandHandler {

    private final Clock clock;
    private final PostRepository posts;

    public AssignTagToPostCommandHandler(Clock clock, PostRepository posts) {
        this.clock = clock;
        this.posts = posts;
    }

    @CommandHandler
    public void handle(AssignTagToPostCommand command,
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
