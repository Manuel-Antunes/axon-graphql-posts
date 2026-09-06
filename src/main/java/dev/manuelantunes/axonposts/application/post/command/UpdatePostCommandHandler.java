package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.mapper.PostViewMapper;
import dev.manuelantunes.axonposts.application.post.port.PostReadRepository;
import dev.manuelantunes.axonposts.domain.post.Post;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

import java.time.Clock;

import static dev.manuelantunes.axonposts.application.shared.AppendingDomainEventPublisher.appendingTo;

/**
 * Handler de <b>um</b> command: {@link UpdatePostCommand}.
 * <p>
 * Ao contrário da criação, aqui o {@code @InjectEntity Post} é <b>obrigatório</b>: o Axon reidrata a
 * entidade a partir dos eventos com a tag {@code postId} e, se não houver nenhum, lança
 * {@code EntityNotFoundException} antes de o método rodar. Ou seja: "não existe" nem chega a ser um
 * caso tratado aqui — é o modelo que garante que, se este código executa, o Post existe.
 * <p>
 * Como na criação, o handler decide e salva: {@code post.update(...)} valida, dispara o
 * {@code PostUpdatedEvent} e devolve o Post já atualizado (inclusive com a versão incrementada), e o
 * handler grava esse estado no read model dentro da mesma transação do command.
 */
@Component
public class UpdatePostCommandHandler {

    private final Clock clock;
    private final PostReadRepository posts;
    private final PostViewMapper viewMapper;

    public UpdatePostCommandHandler(Clock clock, PostReadRepository posts, PostViewMapper viewMapper) {
        this.clock = clock;
        this.posts = posts;
        this.viewMapper = viewMapper;
    }

    @CommandHandler
    public void handle(UpdatePostCommand command,
                       @InjectEntity Post post,
                       EventAppender eventAppender) {
        Post updated = post.update(
                command.title(),
                command.content(),
                clock.instant(),
                appendingTo(eventAppender)
        );

        posts.save(viewMapper.toView(updated));
    }
}
