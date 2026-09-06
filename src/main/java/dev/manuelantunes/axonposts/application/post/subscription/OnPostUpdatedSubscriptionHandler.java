package dev.manuelantunes.axonposts.application.post.subscription;

import dev.manuelantunes.axonposts.application.post.PostView;
import org.axonframework.extension.reactor.messaging.queryhandling.gateway.ReactorQueryGateway;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * Handler de <b>uma</b> subscription: {@link OnPostUpdatedSubscription}. Mesma mecânica do
 * {@link OnPostCreatedSubscriptionHandler}, com um tópico opcional por {@code postId}.
 */
@Component
public class OnPostUpdatedSubscriptionHandler {

    private final ReactorQueryGateway queryGateway;

    public OnPostUpdatedSubscriptionHandler(ReactorQueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    /**
     * Initial result exigido pelo Axon 5. Vazio = só updates futuros.
     * <p>
     * Para "snapshot + updates" (útil fora do GraphQL), é aqui que se devolveria o {@code PostView}
     * atual do {@code postId} — e o resto do fluxo continuaria igual.
     */
    @QueryHandler
    public Optional<PostView> initialResult(OnPostUpdatedSubscription subscription) {
        return Optional.empty();
    }

    /**
     * Flux de Posts atualizados a partir de agora.
     *
     * @param postId tópico opcional: {@code null} = todos os Posts; preenchido = só aquele Post
     */
    public Flux<PostView> subscribe(String postId) {
        return queryGateway.subscriptionQuery(new OnPostUpdatedSubscription(postId), PostView.class);
    }
}
