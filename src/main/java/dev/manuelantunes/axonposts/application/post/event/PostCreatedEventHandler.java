package dev.manuelantunes.axonposts.application.post.event;

import dev.manuelantunes.axonposts.application.post.PostView;
import dev.manuelantunes.axonposts.application.post.port.PostReadRepository;
import dev.manuelantunes.axonposts.application.post.subscription.OnPostCreatedSubscription;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handler de <b>um</b> evento de domínio: {@link PostCreatedEvent}. Faz uma coisa só — <b>notificar</b>
 * quem estiver ouvindo {@code onPostCreated}.
 * <p>
 * Quem grava o read model é o {@code CreatePostCommandHandler}, antes deste handler rodar: o
 * {@code EventAppender} só publica no processor quando o {@code ProcessingContext} do command commita,
 * e a essa altura o {@code save} já aconteceu na mesma transação. Por isso a busca abaixo sempre acha —
 * se não achar, o comando não salvou e é melhor falhar alto do que emitir um evento sem payload.
 * <p>
 * Emitir a view salva (em vez de montar uma a partir do payload do evento) garante que o assinante
 * receba exatamente o mesmo objeto que a query {@code post} devolveria.
 * <p>
 * O {@link QueryUpdateEmitter} é injetado <b>por parâmetro</b> (Axon 5) e já vem ligado ao
 * {@code ProcessingContext} do evento: o emit sai só depois do commit desse contexto.
 */
@Component
public class PostCreatedEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PostCreatedEventHandler.class);

    private final PostReadRepository posts;

    public PostCreatedEventHandler(PostReadRepository posts) {
        this.posts = posts;
    }

    @EventHandler
    public void on(PostCreatedEvent event, QueryUpdateEmitter emitter) {
        String postId = event.postId().value();
        PostView view = posts.findById(postId)
                .orElseThrow(() -> new IllegalStateException(
                        "PostCreated de um Post que não está no read model: " + postId
                                + " — o command handler deveria tê-lo salvo antes do commit"));

        log.debug("PostCreated {} → emitindo para onPostCreated", view.id());

        // tópico global: todo assinante de OnPostCreatedSubscription recebe
        emitter.emit(OnPostCreatedSubscription.class, subscription -> true, view);
    }
}
