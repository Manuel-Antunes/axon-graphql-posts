package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.Post;
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

import static dev.manuelantunes.axonposts.infrastructure.axon.AppendingDomainEventPublisher.appendingTo;

/**
 * O command <b>UpdatePost</b>: a mensagem {@link UpdatePost} e o que acontece quando ela chega.
 * <p>
 * Ao contrário da criação, aqui o {@code @InjectEntity Post} é <b>obrigatório</b>: o Axon reidrata a
 * entidade a partir dos eventos com a tag {@code postId} e, se não houver nenhum, lança
 * {@code EntityNotFoundException} antes de o método rodar. Ou seja: "não existe" nem chega a ser um
 * caso tratado aqui — é o modelo que garante que, se este código executa, o Post existe.
 * <p>
 * Como na criação, o command decide e salva: {@code post.update(...)} valida, dispara o
 * {@code PostUpdatedEvent} e devolve o Post já atualizado (inclusive com a versão incrementada), e o
 * {@link #handle} grava esse estado no read model dentro da mesma transação.
 */
@ApplicationScoped
public class UpdatePostCommand {

    /**
     * A mensagem: atualizar título e/ou conteúdo de um Post. Campos {@code null} significam "manter o
     * valor atual" — quem sabe qual é o valor atual é a entidade, então a mensagem só carrega a intenção.
     */
    @Command(namespace = "posts", name = "UpdatePost", version = "1.0.0")
    /**
     * O {@code actingAuthor} é quem está pedindo, e vem do token — nunca do input. O domínio recusa se o
     * post for de outro: ser autor autoriza a escrever, não a escrever no alheio.
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
    public record UpdatePost(
            @TargetEntityId PostId postId,
            String title,
            String content,
            UserId actingAuthor
    ) {
    }

    private final Clock clock;
    private final PostRepository posts;

    public UpdatePostCommand(Clock clock, PostRepository posts) {
        this.clock = clock;
        this.posts = posts;
    }

    @CommandHandler
    public void handle(UpdatePost command,
                       @InjectEntity Post post,
                       EventAppender eventAppender) {
        Author author = Author.reference(command.actingAuthor());
        Post updated = post.update(
                command.title(),
                command.content(),
                author,
                clock.instant(),
                appendingTo(eventAppender)
        );

        posts.save(updated);
    }
}
