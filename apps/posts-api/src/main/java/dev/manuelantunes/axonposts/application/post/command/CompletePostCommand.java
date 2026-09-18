package dev.manuelantunes.axonposts.application.post.command;

import java.time.Clock;
import java.util.List;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.exception.TagNotFoundException;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;

import static dev.manuelantunes.axonposts.infrastructure.axon.AppendingDomainEventPublisher.appendingTo;

/**
 * O command <b>CompletePost</b>: fecha a criação de um post pré-criado, com a primeira tag.
 *
 * <h2>Onde ele fica na coreografia</h2>
 * É o último passo da saga, e o único que escreve no agregado Post. A decisão de <i>qual</i> tag é de
 * outro serviço e chega como evento; este command é a tradução dessa decisão em fato do Post. Ele não
 * sabe de onde a decisão veio — nem precisa, e é isso que permite o serviço vizinho ser substituído sem
 * tocar aqui.
 *
 * <h2>Chegar duas vezes é SUCESSO</h2>
 * E é este handler que decide isso, não o agregado. A distinção importa:
 * <ul>
 *   <li>para o <b>agregado</b>, completar um post completo é violação de invariante — e
 *       {@code Post.complete} lança;</li>
 *   <li>para <b>este handler</b>, receber a mesma decisão duas vezes é o comportamento normal de um
 *       broker que entrega ao menos uma vez. Falhar faria a mensagem ser nacked e reentregue, para
 *       falhar de novo, para sempre.</li>
 * </ul>
 * Daí o {@code isComplete()} antes de decidir: sai calado, a mensagem é confirmada, e a saga para onde
 * tinha de parar. É a terceira das três guardas contra duplicação (origem, inbox, agregado) — e a
 * única que ainda funciona se o inbox for limpo.
 */
@ApplicationScoped
public class CompletePostCommand {

    private static final Logger log = LoggerFactory.getLogger(CompletePostCommand.class);

    /**
     * A mensagem: este post recebe esta tag e passa a estar completo.
     * <p>
     * Só os dois ids. O nome da tag não vem: quem grava o vínculo é o Post, e a Tag precisa existir
     * localmente de qualquer forma — quem a cria, se preciso, é quem reage à decisão, antes de
     * despachar isto.
     */
    @Command(namespace = "posts", name = "CompletePost", version = "1.0.0")
    public record CompletePost(
            @TargetEntityId PostId postId,
            TagId tagId
    ) {
    }

    private final Clock clock;
    private final PostRepository posts;
    private final TagRepository tags;

    public CompletePostCommand(Clock clock, PostRepository posts, TagRepository tags) {
        this.clock = clock;
        this.posts = posts;
        this.tags = tags;
    }

    @CommandHandler
    public void handle(CompletePost command,
                       @InjectEntity Post post,
                       EventAppender eventAppender) {
        if (post.isComplete()) {
            log.info("post {} já estava completo — decisão de tagueamento duplicada, descartada",
                    command.postId());
            return;
        }

        Tag tag = tags.findById(command.tagId())
                .orElseThrow(() -> new TagNotFoundException(command.tagId()));

        Post completed = post.complete(List.of(tag), clock.instant(), appendingTo(eventAppender));

        posts.save(completed);
    }
}
