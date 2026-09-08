package dev.manuelantunes.axonposts.interfaces.graphql;

import dev.manuelantunes.axonposts.dto.controller.PostView;
import dev.manuelantunes.axonposts.application.post.subscription.OnPostCreatedSubscription;
import dev.manuelantunes.axonposts.application.post.subscription.OnPostUpdatedSubscription;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;

/**
 * Camada de interface das <b>subscriptions</b> GraphQL: devolve o {@link Flux} do handler de aplicação
 * daquela subscription. Quem sabe falar com o {@code QueryBus} é o handler; aqui só se escolhe qual.
 * <p>
 * O transporte é do Spring GraphQL:
 * <ul>
 *   <li>POST {@code /graphql} + {@code Accept: text/event-stream} → <b>Server-Sent Events</b>
 *       (protocolo GraphQL over SSE: {@code event:next} / {@code event:complete});</li>
 *   <li>upgrade WebSocket em {@code /graphql} → graphql-ws (habilitado só para o GraphiQL).</li>
 * </ul>
 * Quando o cliente desconecta, o Flux é cancelado e o Axon fecha a subscription query.
 */
@Controller
public class PostSubscriptionController {

    private final OnPostCreatedSubscription onPostCreated;
    private final OnPostUpdatedSubscription onPostUpdated;

    public PostSubscriptionController(OnPostCreatedSubscription onPostCreated,
                                      OnPostUpdatedSubscription onPostUpdated) {
        this.onPostCreated = onPostCreated;
        this.onPostUpdated = onPostUpdated;
    }

    @SubscriptionMapping
    public Flux<PostView> onPostCreated() {
        return onPostCreated.subscribe();
    }

    @SubscriptionMapping
    public Flux<PostView> onPostUpdated(@Argument String postId) {
        return onPostUpdated.subscribe(postId);
    }
}
