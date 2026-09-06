package dev.manuelantunes.axonposts.application.post.subscription;

import dev.manuelantunes.axonposts.dto.controller.PostView;
import org.axonframework.extension.reactor.messaging.queryhandling.gateway.ReactorQueryGateway;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * Handler de <b>uma</b> subscription: {@link OnPostCreatedSubscription}. As duas pontas dela — abrir o
 * stream e responder o initial result — ficam nesta classe.
 *
 * <h2>A ponte command → evento → Flux, no Axon 5</h2>
 * <ol>
 *   <li>{@link #subscribe()} chama {@code subscriptionQuery}, que registra o {@code QueryMessage} no
 *       {@code QueryBus} (fica lá enquanto o Flux estiver assinado) e devolve <b>initial result +
 *       updates</b> num único Flux;</li>
 *   <li>o initial result é o {@link #initialResult} abaixo. Como a semântica de subscription GraphQL é
 *       "só o que acontecer daqui pra frente", ele devolve {@code Optional.empty()} — o Axon 5 exige o
 *       handler (diferente do 4.x, onde o initial result era preguiçoso), mas ele pode ser vazio;</li>
 *   <li>um {@code CreatePostCommand} chega, o domínio dispara {@code PostCreatedEvent};</li>
 *   <li>o {@code PostCreatedEventHandler} projeta o evento e chama {@code emitter.emit(...)};</li>
 *   <li>o emit é adiado para o <i>after-commit</i> do {@code ProcessingContext}, então quando o Flux
 *       recebe o {@link PostView} o read model já está commitado;</li>
 *   <li>o {@code @SubscriptionMapping} devolve esse Flux e o Spring GraphQL o serializa como SSE.</li>
 * </ol>
 */
@Component
public class OnPostCreatedSubscriptionHandler {

    private final ReactorQueryGateway queryGateway;

    public OnPostCreatedSubscriptionHandler(ReactorQueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    /** Initial result exigido pelo Axon 5. Vazio = o assinante só recebe eventos futuros. */
    @QueryHandler
    public Optional<PostView> initialResult(OnPostCreatedSubscription subscription) {
        return Optional.empty();
    }

    /** Flux de todo Post criado a partir de agora. */
    public Flux<PostView> subscribe() {
        return queryGateway.subscriptionQuery(new OnPostCreatedSubscription(), PostView.class);
    }
}
