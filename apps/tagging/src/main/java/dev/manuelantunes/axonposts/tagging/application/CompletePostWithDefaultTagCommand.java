package dev.manuelantunes.axonposts.tagging.application;

import java.time.Clock;
import java.util.List;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import dev.manuelantunes.axonposts.infrastructure.axon.AppendingDomainEventPublisher;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Completa o post com a tag padrão — <b>no agregado Post de verdade</b>.
 *
 * <h2>Não existe agregado próprio aqui, e isso foi uma correção</h2>
 * A primeira versão deste serviço tinha um agregado {@code TagAssignment}, cuja única razão de existir
 * era responder "eu já decidi a tag deste post?". Era invenção: a pergunta que importa é "este post já
 * está completo?", e o {@code Post} responde — ele tem a lista de tags e tem {@code isComplete()}.
 * Criar uma entidade para guardar o que outra entidade já sabe é duplicar estado, e estado duplicado
 * diverge.
 * <p>
 * Então este serviço carrega o {@code Post}, olha a lista de tags dele e acrescenta a que decidiu. A
 * decisão de <i>quando</i> tagueiar é dele; a regra de como um post se completa continua sendo do
 * {@code Post}, em {@code libs/posts} — e é <b>a mesma classe</b> que o serviço de posts usa, não uma
 * cópia.
 *
 * <h2>A tag padrão também é do domínio</h2>
 * Nome e identidade vêm de {@link Tag#DEFAULT_NAME} e {@link Tag#DEFAULT_ID}, e não de uma constante
 * deste serviço. A diferença importa porque <b>dois</b> lugares decidem a tag padrão — este serviço em
 * produção e o dublê em processo na suíte do outro app — e os dois têm de chegar ao mesmo id. Com a
 * regra no domínio, chegar ao mesmo id é consequência; com a regra aqui, seria coincidência mantida à
 * mão.
 *
 * <h2>Por que ele não chama {@code posts.save(...)}</h2>
 * Porque este serviço não tem read model: o {@code Post} aqui é reidratado do event store local (que o
 * {@code ChannelEventIngestion} alimentou com o {@code PostPreCreated} vindo do broker), decide, e o
 * evento resultante sai pelo broker. Quem materializa a linha é o serviço que tem a tabela.
 *
 * <h2>Completar duas vezes é sucesso</h2>
 * {@code Post.complete} lança se o post já estiver completo, porque é invariante do agregado. Aqui a
 * segunda chegada é entrega duplicada do broker, que é normal — daí o {@code isComplete()} antes. É a
 * terceira guarda da coreografia, depois da marca de origem e do inbox, e a única que sobrevive a um
 * inbox limpo.
 */
@ApplicationScoped
public class CompletePostWithDefaultTagCommand {

    private static final Logger log = LoggerFactory.getLogger(CompletePostWithDefaultTagCommand.class);

    /** A mensagem: complete este post com a tag que este serviço decidiu. */
    @Command(namespace = "tagging", name = "CompletePostWithDefaultTag", version = "1.0.0")
    public record CompletePostWithDefaultTag(
            @TargetEntityId PostId postId
    ) {
    }

    private final Clock clock;

    public CompletePostWithDefaultTagCommand(Clock clock) {
        this.clock = clock;
    }

    @CommandHandler
    public void handle(CompletePostWithDefaultTag command,
                       @InjectEntity Post post,
                       EventAppender eventAppender) {
        if (post.isComplete()) {
            log.info("post {} já está completo com {} — entrega duplicada, descartada",
                    command.postId(), post.tags());
            return;
        }

        /*
         * `Tag.reference` e não uma Tag carregada do banco: este serviço não tem a tabela `tags`, e a
         * referência é exatamente a forma que o replay do próprio domínio produz — o `Post` só lê id e
         * nome da tag para montar o evento. Quem precisa da linha é quem tem read model, e ele a projeta
         * a partir do evento.
         */
        Tag defaultTag = Tag.reference(Tag.DEFAULT_ID, TagName.of(Tag.DEFAULT_NAME));

        log.debug("post {} recebe a tag padrão {} ({})",
                command.postId(), Tag.DEFAULT_NAME, Tag.DEFAULT_ID);

        post.complete(List.of(defaultTag), clock.instant(),
                AppendingDomainEventPublisher.appendingTo(eventAppender));
    }
}
