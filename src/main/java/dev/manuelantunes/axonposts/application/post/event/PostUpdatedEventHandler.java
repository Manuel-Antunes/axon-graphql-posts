package dev.manuelantunes.axonposts.application.post.event;

import dev.manuelantunes.axonposts.application.post.subscription.OnPostUpdatedSubscription.OnPostUpdated;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import dev.manuelantunes.axonposts.dto.controller.PostView;
import dev.manuelantunes.axonposts.mapper.PostViewMapper;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handler de <b>um</b> evento de domínio: {@link PostUpdatedEvent}. Como o de criação, faz uma coisa só —
 * notificar quem estiver ouvindo {@code onPostUpdated}, respeitando o tópico de cada assinante.
 * <p>
 * Aqui a view vem do banco, e não do evento, porque o {@code PostUpdatedEvent} carrega só o que muda:
 * autor e data de criação não estão nele. O estado já está gravado quando este handler roda — o command
 * salvou antes de o {@code ProcessingContext} commitar — então a leitura sempre acha, e o que é emitido
 * é exatamente o que a query {@code post} devolveria.
 * <p>
 * Vale tanto para um update de título/conteúdo quanto para a atribuição de uma tag: os dois são o mesmo
 * evento, e é por isso que assinar {@code onPostUpdated} basta para ver a tag padrão chegar.
 */
@Component
public class PostUpdatedEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PostUpdatedEventHandler.class);

    private final PostRepository posts;
    private final PostViewMapper viewMapper;

    public PostUpdatedEventHandler(PostRepository posts, PostViewMapper viewMapper) {
        this.posts = posts;
        this.viewMapper = viewMapper;
    }

    @EventHandler
    public void on(PostUpdatedEvent event, QueryUpdateEmitter emitter) {
        PostView view = posts.findById(event.postId())
                .map(viewMapper::toView)
                .orElseThrow(() -> new IllegalStateException(
                        "PostUpdated de um Post que não está no banco: " + event.postId()
                                + " — o command deveria tê-lo salvo antes do commit"));

        log.debug("PostUpdated {} (v{}) de {} → emitindo para onPostUpdated",
                view.id(), view.version(), view.authorId());

        // dois tópicos, ambos opcionais: postId e authorId. Sem filtro nenhum, o assinante recebe tudo
        emitter.emit(OnPostUpdated.class,
                subscription -> subscription.matches(view.id(), view.authorId()), view);
    }
}
