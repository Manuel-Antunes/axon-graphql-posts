package dev.manuelantunes.axonposts.application.post.event;

import dev.manuelantunes.axonposts.application.post.PostView;
import dev.manuelantunes.axonposts.application.post.port.PostReadRepository;
import dev.manuelantunes.axonposts.application.post.subscription.OnPostUpdatedSubscription;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handler de <b>um</b> evento de domínio: {@link PostUpdatedEvent}. Como o de criação, faz uma coisa só —
 * <b>notificar</b> quem estiver ouvindo {@code onPostUpdated}, respeitando o tópico de cada assinante.
 * <p>
 * A view já foi gravada pelo {@code UpdatePostCommandHandler} com o estado que o domínio devolveu,
 * versão incluída; aqui só se lê o resultado e emite.
 */
@Component
public class PostUpdatedEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PostUpdatedEventHandler.class);

    private final PostReadRepository posts;

    public PostUpdatedEventHandler(PostReadRepository posts) {
        this.posts = posts;
    }

    @EventHandler
    public void on(PostUpdatedEvent event, QueryUpdateEmitter emitter) {
        String postId = event.postId().value();
        PostView view = posts.findById(postId)
                .orElseThrow(() -> new IllegalStateException(
                        "PostUpdated de um Post que não está no read model: " + postId
                                + " — o command handler deveria tê-lo salvo antes do commit"));

        log.debug("PostUpdated {} (v{}) → emitindo para onPostUpdated", view.id(), view.version());

        // tópico por id: só assinantes sem filtro ou com o mesmo postId recebem
        emitter.emit(OnPostUpdatedSubscription.class, subscription -> subscription.matches(view.id()), view);
    }
}
