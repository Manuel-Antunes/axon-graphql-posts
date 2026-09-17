package dev.manuelantunes.axonposts.application.post.event;

import dev.manuelantunes.axonposts.application.post.command.AssignTagToPostCommand.AssignTagToPost;
import dev.manuelantunes.axonposts.application.tag.command.CreateTagCommand.CreateTag;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.CompletableFuture;

/**
 * Handler de <b>um</b> evento de domínio: {@link PostCreatedEvent}. Garante que todo post nasça com pelo
 * menos uma tag.
 *
 * <h2>O que ele faz</h2>
 * <ol>
 *   <li>procura no banco a tag padrão ({@value Tag#DEFAULT_NAME});</li>
 *   <li>se não houver nenhuma tag com esse nome, despacha {@link CreateTag} para criá-la — a Tag é
 *       um agregado próprio, então ela nasce como qualquer agregado nasce: por um command, com o seu
 *       próprio evento e o seu próprio stream;</li>
 *   <li>despacha {@link AssignTagToPost}, que faz o Post disparar o {@code PostUpdatedEvent} com a
 *       tag na lista — e é esse evento que o {@code PostUpdatedEventHandler} publica em
 *       {@code onPostUpdated}.</li>
 * </ol>
 *
 * <h2>Orquestração é do Axon, não deste código</h2>
 * O handler não escreve no banco nem monta eventos: ele só <b>despacha commands</b> pelo
 * {@link CommandGateway} e deixa o framework fazer o resto — carregar o agregado certo, aplicar as regras
 * dele, apendar o evento, disparar os handlers seguintes. Reagir a um evento despachando um command é o
 * jeito do Axon de encadear uma decisão na outra sem que uma conheça a outra: o
 * {@code CreatePostCommand} não sabe que existem tags, e o domínio de Tag não sabe que existem
 * posts.
 *
 * <h2>Por que no {@code AFTER_COMMIT}, e não direto</h2>
 * Este handler roda durante o <i>commit</i> do {@code CreatePost}. Nesse ponto o
 * {@code PostCreatedEvent} ainda não é legível de volta do event store: despachar o
 * {@link AssignTagToPost} ali faz o Axon tentar reidratar um Post cujo stream ele não enxerga, e
 * o command falha com {@code EntityNotFoundException} — que é exatamente o que acontecia antes de o
 * trabalho ser adiado para {@link ProcessingContext#onAfterCommit}.
 * <p>
 * Registrando na fase {@code AFTER_COMMIT}, os commands saem depois de o stream do post estar
 * efetivamente gravado, cada um na sua própria unidade de trabalho.
 *
 * <h2>Por que a mutation já devolve o post com a tag</h2>
 * {@code onAfterCommit} recebe uma função que devolve um {@link CompletableFuture}, e o Axon <b>espera</b>
 * por ele antes de dar o processamento por concluído. Ou seja: {@code commandGateway.send(CreatePost)} só
 * completa depois de a tag estar atribuída, e o {@code PostMutationController} faz a query do post
 * <i>depois</i> disso. Quando o GraphQL responde, a tag já está lá — sem polling e sem espera artificial.
 * <p>
 * O que sustenta essa ordem é o processor {@code post-projection} ser <b>subscribing</b>. Em pooled
 * streaming a mutation poderia responder antes, e o cliente veria a tag chegar pelo {@code onPostUpdated}.
 * É a diferença entre consistência imediata e eventual — aqui, uma linha de configuração.
 */
@ApplicationScoped
public class AssignDefaultTagOnPostCreated {

    private static final Logger log = LoggerFactory.getLogger(AssignDefaultTagOnPostCreated.class);

    private final TagRepository tags;
    private final CommandGateway commandGateway;

    public AssignDefaultTagOnPostCreated(TagRepository tags, CommandGateway commandGateway) {
        this.tags = tags;
        this.commandGateway = commandGateway;
    }

    @EventHandler
    public void on(PostCreatedEvent event, ProcessingContext context) {
        context.onAfterCommit(committed -> assignDefaultTag(event.postId()));
    }

    private CompletableFuture<Void> assignDefaultTag(PostId postId) {
        TagName defaultName = TagName.of(Tag.DEFAULT_NAME);

        return defaultTagId(defaultName)
                .thenCompose(tagId -> commandGateway
                        .send(new AssignTagToPost(postId, tagId), Void.class)
                        .thenRun(() -> log.debug("post {} recebeu a tag padrão {} ({})", postId, defaultName, tagId)));
    }

    /**
     * O id da tag padrão: o que já está no banco, ou o de uma tag recém-criada. O id é gerado aqui para
     * que o comando de atribuição já saiba a qual tag se referir, sem uma segunda consulta.
     */
    private CompletableFuture<TagId> defaultTagId(TagName name) {
        return tags.findByName(name)
                .map(tag -> CompletableFuture.completedFuture(tag.id()))
                .orElseGet(() -> {
                    TagId tagId = TagId.newId();
                    log.debug("nenhuma tag {} no banco — criando {}", name, tagId);
                    return commandGateway.send(new CreateTag(tagId, name.value()), TagId.class);
                });
    }
}
