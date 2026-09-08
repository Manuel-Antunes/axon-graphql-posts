package dev.manuelantunes.axonposts.application.post.subscription;

import dev.manuelantunes.axonposts.dto.controller.PostView;
import org.axonframework.extension.reactor.messaging.queryhandling.gateway.ReactorQueryGateway;
import org.axonframework.messaging.queryhandling.annotation.Query;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * A subscription <b>OnPostUpdated</b>: a mensagem {@link OnPostUpdated} e as duas pontas dela. Mesma
 * mecânica da {@link OnPostCreatedSubscription}, com um tópico opcional por {@code postId}.
 */
@Component
public class OnPostUpdatedSubscription {

    /**
     * A mensagem: "me avise quando um Post for atualizado".
     *
     * @param postId tópico opcional — {@code null} recebe update de qualquer Post; preenchido, só os
     *               daquele id. O filtro é avaliado no emit, pelo {@code PostUpdatedEventHandler}.
     */
    @Query(namespace = "posts", name = "OnPostUpdated", version = "1.0.0")
    public record OnPostUpdated(String postId) {

        /** O predicado do tópico mora junto da mensagem: quem emite não precisa saber a regra. */
        public boolean matches(String updatedPostId) {
            return postId == null || postId.isBlank() || postId.equals(updatedPostId);
        }
    }

    private final ReactorQueryGateway queryGateway;

    public OnPostUpdatedSubscription(ReactorQueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    /**
     * Initial result exigido pelo Axon 5. Vazio = só updates futuros.
     * <p>
     * Para "snapshot + updates" (útil fora do GraphQL), é aqui que se devolveria o {@code PostView}
     * atual do {@code postId} — e o resto do fluxo continuaria igual.
     */
    @QueryHandler
    public Optional<PostView> initialResult(OnPostUpdated subscription) {
        return Optional.empty();
    }

    /**
     * Flux de Posts atualizados a partir de agora.
     *
     * @param postId tópico opcional: {@code null} = todos os Posts; preenchido = só aquele Post
     */
    public Flux<PostView> subscribe(String postId) {
        return queryGateway.subscriptionQuery(new OnPostUpdated(postId), PostView.class);
    }
}
