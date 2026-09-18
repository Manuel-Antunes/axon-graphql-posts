package dev.manuelantunes.axonposts.application.post.command;

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
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;

import static dev.manuelantunes.axonposts.application.shared.AppendingDomainEventPublisher.appendingTo;

/**
 * O command <b>AssignTagToPost</b>: a mensagem {@link AssignTagToPost} e o que acontece quando ela chega.
 * <p>
 * O Axon reidrata o {@link Post} do stream dele; este handler carrega a {@link Tag}; o domínio decide se
 * ela pode entrar e dispara o {@code PostUpdatedEvent} com a lista de tags resultante; o {@link #handle}
 * salva o estado devolvido.
 *
 * <h2>Por que a Tag é carregada aqui, e não montada da mensagem</h2>
 * O Post referencia a entidade {@code Tag} de verdade ({@code @ManyToMany}), então gravar o vínculo é
 * gravar uma linha em {@code post_tags} com foreign key para {@code tags}. Um id inexistente estouraria
 * como erro de integridade no flush; carregar antes transforma isso numa {@link TagNotFoundException}
 * com nome e lugar.
 * <p>
 * O ganho secundário é de ciclo de vida: a leitura acontece dentro da mesma transação que o
 * {@code posts.save(...)}, então a Tag que entra no agregado é uma entidade <b>gerenciada</b> — o
 * {@code merge} do Post a encontra no contexto de persistência em vez de resolver um objeto destacado
 * por id.
 * <p>
 * Isto <b>não</b> é o Post passar a mandar na Tag: a leitura é do handler, não do domínio, e continua
 * sem cascade nenhum. Nada aqui escreve na tabela {@code tags}, e a Tag segue sem evento algum sobre
 * posts — quem registra o vínculo é o {@code PostUpdatedEvent}, do agregado que o vínculo pertence.
 */
@ApplicationScoped
public class AssignTagToPostCommand {

    /**
     * A mensagem: assinalar uma tag já existente a um post.
     * <p>
     * Só os dois ids. O nome da tag não vem mais junto porque o handler carrega a Tag — mandá-lo seria
     * oferecer ao caller a chance de discordar do banco sobre como a tag se chama.
     */
    @Command(namespace = "posts", name = "AssignTagToPost", version = "1.0.0")
    public record AssignTagToPost(
            @TargetEntityId PostId postId,
            TagId tagId
    ) {
    }

    private final Clock clock;
    private final PostRepository posts;
    private final TagRepository tags;

    public AssignTagToPostCommand(Clock clock, PostRepository posts, TagRepository tags) {
        this.clock = clock;
        this.posts = posts;
        this.tags = tags;
    }

    @CommandHandler
    public void handle(AssignTagToPost command,
                       @InjectEntity Post post,
                       EventAppender eventAppender) {
        Tag tag = tags.findById(command.tagId())
                .orElseThrow(() -> new TagNotFoundException(command.tagId()));

        Post tagged = post.assignTag(tag, clock.instant(), appendingTo(eventAppender));

        posts.save(tagged);
    }
}
