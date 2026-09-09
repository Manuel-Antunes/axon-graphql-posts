package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.exception.PostAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.exception.NotAnAuthorException;
import dev.manuelantunes.axonposts.domain.user.exception.UserNotFoundException;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

import static dev.manuelantunes.axonposts.application.shared.AppendingDomainEventPublisher.appendingTo;

/**
 * O command <b>CreatePost</b>: a mensagem {@link CreatePost} e o que acontece quando ela chega, num
 * arquivo só. A classe leva o nome do command porque é isso que ela é — a decisão inteira de criar um
 * Post; o record aninhado é só o formato da mensagem que a dispara, e por isso se chama apenas
 * {@code CreatePost}. Quem despacha escreve {@code new CreatePostCommand.CreatePost(...)}.
 *
 * <h2>Criar e salvar</h2>
 * O {@link #handle} faz as duas coisas: pede ao domínio que crie o Post ({@code Post.create}, que valida,
 * dispara o {@code PostCreatedEvent} e devolve a entidade pronta) e <b>grava</b> o resultado no read
 * model. Como isso acontece dentro do {@code ProcessingContext} do command, o append do evento e a
 * escrita no SQLite commitam juntos: ou os dois valem, ou nenhum.
 * <p>
 * O event handler correspondente não projeta nada — quando ele roda, a view já está salva; ele só emite
 * para as subscriptions.
 *
 * <h2>Por que {@code Optional<Post>} num command criacional</h2>
 * O Post ainda não existe, então pedir {@code Post} obrigatório falharia sempre. Pedindo
 * {@code Optional<Post>} ganham-se duas coisas: dá para rejeitar id duplicado, e carregar a entidade
 * coloca o stream {@code postId=<id>} na <b>consistency boundary</b> do append — duas criações
 * concorrentes com o mesmo id conflitam no event store em vez de gerarem dois streams.
 */
@Component
public class CreatePostCommand {

    /**
     * A mensagem: criar um Post. O id é gerado por quem despacha, assim o caller já sabe o id antes do
     * command ser processado (e o {@code createPost} do GraphQL consegue devolver o Post projetado).
     * <p>
     * {@code @TargetEntityId} (o {@code @TargetAggregateIdentifier} do Axon 5) é lido pelo
     * {@code @InjectEntity} do {@link #handle} para descobrir <i>qual</i> stream carregar.
     * <p>
     * O nome da mensagem é explícito ({@code posts.CreatePost#1.0.0}), então aninhar o record não muda
     * nada no wire: o que trafega no command bus continua sendo esse nome, não o da classe.
     * <p>
     * O {@code authorId} <b>não</b> vem do input do GraphQL — vem de quem está autenticado. É a
     * diferença entre "quem eu digo que sou" e "quem o token diz que eu sou", e só a segunda serve:
     * aceitar o autor como campo do input deixaria qualquer um publicar em nome de qualquer outro.
     */
    @Command(namespace = "posts", name = "CreatePost", version = "1.0.0")
    public record CreatePost(
            @TargetEntityId PostId postId,
            String title,
            String content,
            UserId authorId
    ) {
    }

    private final Clock clock;
    private final PostRepository posts;
    private final UserRepository users;

    public CreatePostCommand(Clock clock, PostRepository posts, UserRepository users) {
        this.clock = clock;
        this.posts = posts;
        this.users = users;
    }

    /**
     * @return o id do Post criado, que vira o payload do {@code CommandResultMessage}
     */
    @CommandHandler
    public PostId handle(CreatePost command,
                         @InjectEntity Optional<Post> existing,
                         EventAppender eventAppender) {
        if (existing.isPresent()) {
            throw new PostAlreadyExistsException(command.postId());
        }

        Post post = Post.create(
                command.postId(),
                command.title(),
                command.content(),
                author(command.authorId()),
                clock.instant(),
                appendingTo(eventAppender)
        );

        posts.save(post);
        return post.id();
    }

    /**
     * O <b>downcast</b>, e o único lugar onde ele acontece.
     * <p>
     * O repositório devolve {@code User} porque a herança é {@code JOINED} e o tipo concreto é decidido
     * pelo banco — existe linha em {@code authors} ou não existe. O {@code instanceof} aqui não é um
     * <i>cast</i> otimista: é a checagem que confirma, contra o banco, o que a role do token já tinha
     * afirmado. As duas podem divergir (papel revogado, token ainda válido), e quando divergem é o tipo
     * que ganha.
     * <p>
     * Carregar dentro do handler ainda dá o de sempre: a {@code Author} entra no agregado
     * <b>gerenciada</b>, na mesma transação do {@code posts.save(...)}.
     */
    private Author author(UserId authorId) {
        User user = users.findById(authorId).orElseThrow(() -> new UserNotFoundException(authorId));
        if (user instanceof Author author) {
            return author;
        }
        throw new NotAnAuthorException(authorId);
    }
}
