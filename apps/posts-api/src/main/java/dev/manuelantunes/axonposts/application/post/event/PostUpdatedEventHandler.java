package dev.manuelantunes.axonposts.application.post.event;

import java.util.Optional;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.manuelantunes.axonposts.application.post.subscription.OnPostUpdatedSubscription.OnPostUpdated;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.application.post.view.PostViewMapper;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Handler de <b>um</b> evento de domínio: {@link PostUpdatedEvent}. Como o de criação, faz uma coisa só —
 * notificar quem estiver ouvindo {@code onPostUpdated}, respeitando o tópico de cada assinante.
 * <p>
 * A view vem do banco, e não do evento, porque o {@code PostUpdatedEvent} carrega só o que muda: autor e
 * data de criação não estão nele. O estado já está gravado quando este handler roda — o command salvou
 * dentro da transação que apendou o evento —, então o que é emitido é exatamente o que a query
 * {@code post} devolveria.
 * <p>
 * Vale tanto para um update de título/conteúdo quanto para a atribuição de uma tag: os dois são o mesmo
 * evento, e é por isso que assinar {@code onPostUpdated} basta para ver a tag padrão chegar.
 * <p>
 * Este handler roda no processor declarado no {@code package-info} deste pacote, e é de lá que vem a
 * propriedade que importa: ele lê do <b>event store</b>, então enxerga o que qualquer container apendou
 * — não só este. É o que faz a subscription funcionar com mais de uma instância.
 */
@ApplicationScoped
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
        Optional<PostView> view = posts.findById(event.postId()).map(viewMapper::toView);

        if (view.isEmpty()) {
            // ver PostCreatedEventHandler: num processor com retry, lançar trava o cursor para sempre.
            // Quem estoura quando a linha falta é PostUpdatedProjection, na transação do append.
            log.warn("PostUpdated de {} sem linha na projeção — nada a notificar."
                    + " O post foi apagado, ou a projeção ainda não o materializou.", event.postId());
            return;
        }

        PostView post = view.get();
        log.debug("PostUpdated {} (v{}) de {} → emitindo para onPostUpdated",
                post.id(), post.version(), post.authorId());

        // dois tópicos, ambos opcionais: postId e authorId. Sem filtro nenhum, o assinante recebe tudo
        emitter.emit(OnPostUpdated.class,
                subscription -> subscription.matches(post.id(), post.authorId()), post);
    }
}
