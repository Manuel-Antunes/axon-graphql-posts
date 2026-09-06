package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.exception.PostAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

import static dev.manuelantunes.axonposts.application.shared.AppendingDomainEventPublisher.appendingTo;

/**
 * Handler de <b>um</b> command: {@link CreatePostCommand}. Uma classe por handler, colada ao command que
 * ela trata — tudo o que acontece quando esse command chega está neste arquivo, e nada mais está.
 *
 * <h2>Criar e salvar</h2>
 * O handler faz as duas coisas: pede ao domínio que crie o Post ({@code Post.create}, que valida,
 * dispara o {@code PostCreatedEvent} e devolve a entidade pronta) e <b>grava</b> o resultado no read
 * model. Como isso acontece dentro do {@code ProcessingContext} do command, o append do evento e a
 * escrita no SQLite commitam juntos: ou os dois valem, ou nenhum.
 * <p>
 * O event handler correspondente não projeta nada — quando ele roda, a view já está salva; ele só emite
 * para as subscriptions.
 *
 * <h2>Por que {@code Optional<Post>} num handler criacional</h2>
 * O Post ainda não existe, então pedir {@code Post} obrigatório falharia sempre. Pedindo
 * {@code Optional<Post>} ganham-se duas coisas: dá para rejeitar id duplicado, e carregar a entidade
 * coloca o stream {@code postId=<id>} na <b>consistency boundary</b> do append — duas criações
 * concorrentes com o mesmo id conflitam no event store em vez de gerarem dois streams.
 */
@Component
public class CreatePostCommandHandler {

    private final Clock clock;
    private final PostRepository posts;

    public CreatePostCommandHandler(Clock clock, PostRepository posts) {
        this.clock = clock;
        this.posts = posts;
    }

    /**
     * @return o id do Post criado, que vira o payload do {@code CommandResultMessage}
     */
    @CommandHandler
    public PostId handle(CreatePostCommand command,
                         @InjectEntity Optional<Post> existing,
                         EventAppender eventAppender) {
        if (existing.isPresent()) {
            throw new PostAlreadyExistsException(command.postId());
        }

        Post post = Post.create(
                command.postId(),
                command.title(),
                command.content(),
                command.author(),
                clock.instant(),
                appendingTo(eventAppender)
        );

        posts.save(post);
        return post.id();
    }
}
