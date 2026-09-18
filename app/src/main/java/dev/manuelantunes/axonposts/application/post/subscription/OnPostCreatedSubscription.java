package dev.manuelantunes.axonposts.application.post.subscription;

import java.util.Optional;

import org.axonframework.messaging.queryhandling.annotation.Query;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.reactivestreams.FlowAdapters;

import dev.manuelantunes.axonposts.application.post.view.PostView;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A subscription <b>OnPostCreated</b>: a mensagem {@link OnPostCreated}, abrir o stream e responder o
 * initial result, tudo nesta classe — mesma convenção dos commands e das queries.
 *
 * <h2>A ponte command → evento → Multi, no Axon 5</h2>
 * <ol>
 *   <li>{@link #subscribe(String)} chama {@code subscriptionQuery}, que registra o {@code QueryMessage}
 *       no {@code QueryBus} (fica lá enquanto o stream estiver assinado) e devolve <b>initial result +
 *       updates</b>;</li>
 *   <li>o initial result é o {@link #initialResult} abaixo. Como a semântica de subscription GraphQL é
 *       "só o que acontecer daqui pra frente", ele devolve {@code Optional.empty()} — o Axon 5 exige o
 *       handler (diferente do 4.x, onde o initial result era preguiçoso), mas ele pode ser vazio;</li>
 *   <li>um {@code CreatePost} chega, o domínio dispara {@code PostCreatedEvent};</li>
 *   <li>o {@code PostCreatedEventHandler} monta a view e chama {@code emitter.emit(...)};</li>
 *   <li>o emit é adiado para o <i>after-commit</i> do {@code ProcessingContext}, então quando o stream
 *       recebe o {@link PostView} o read model já está commitado;</li>
 *   <li>o {@code @Subscription} do SmallRye devolve esse {@link Multi} e o serializa como
 *       GraphQL-over-WebSocket ou GraphQL-over-SSE, conforme o cliente pediu.</li>
 * </ol>
 *
 * <h2>Sem a extensão Reactor do Axon</h2>
 * A versão Spring precisava do {@code axon-reactor} para ter um {@code ReactorQueryGateway} devolvendo
 * {@code Flux}. Aqui não: o {@code QueryGateway} do <b>núcleo</b> do Axon 5 já devolve um
 * {@link java.util.concurrent.Flow.Publisher Publisher} de Reactive Streams, e
 * {@code Multi.createFrom().publisher(...)} o consome direto. Uma dependência a menos, e o contrato é o
 * padrão da JVM em vez do de uma biblioteca.
 */
@ApplicationScoped
public class OnPostCreatedSubscription {

    /**
     * A mensagem: "me avise de todo Post criado", com tópico opcional por autor.
     * <p>
     * É o payload do {@code QueryMessage} que o {@code QueryBus} mantém registrado enquanto o stream
     * estiver assinado; o event handler de {@code PostCreatedEvent} emite para ele por tipo
     * ({@code emitter.emit(OnPostCreated.class, ...)}) aplicando este predicado a cada assinante.
     *
     * @param authorId tópico opcional — {@code null} recebe todo post criado; preenchido, só os daquele
     *                 autor. É a <b>newsletter</b>: acompanhar um autor específico.
     */
    @Query(namespace = "posts", name = "OnPostCreated", version = "1.0.0")
    public record OnPostCreated(String authorId) {

        /** O predicado do tópico mora junto da mensagem: quem emite não precisa saber a regra. */
        public boolean matches(String createdByAuthorId) {
            return authorId == null || authorId.isBlank() || authorId.equals(createdByAuthorId);
        }
    }

    /**
     * Quantos eventos podem ficar em espera enquanto o assinante não pede o próximo. Ver
     * {@link #subscribe} sobre por que este buffer é obrigatório e não um afinamento.
     */
    private static final int UPDATE_BUFFER = 256;

    private final QueryGateway queryGateway;

    public OnPostCreatedSubscription(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    /** Initial result exigido pelo Axon 5. Vazio = o assinante só recebe eventos futuros. */
    @QueryHandler
    public Optional<PostView> initialResult(OnPostCreated subscription) {
        return Optional.empty();
    }

    /**
     * Stream de Posts criados a partir de agora.
     *
     * <h2>{@code onOverflow().buffer(...)} não é afinamento: sem ele a subscription é de um evento só</h2>
     * O {@code Publisher} que o {@code subscriptionQuery} devolve <b>não honra demanda incremental</b>.
     * Assinado com {@code request(Long.MAX_VALUE)} ele entrega tudo; assinado com {@code request(1)} e um
     * {@code request(1)} a cada item — que é exatamente o que o {@code SubscriptionSubscriber} do SmallRye
     * faz — ele entrega o <b>primeiro</b> e nunca mais nada. O cliente recebe um evento, a conexão fica
     * aberta e silenciosa, e nada no log reclama.
     * <p>
     * O operador separa as duas demandas: o Mutiny pede ilimitado ao Axon e serve o assinante de baixo a
     * partir do próprio buffer. O teto existe para a falha ser barulhenta se um assinante travar de vez —
     * melhor um {@code BackPressureFailure} do que memória crescendo em silêncio.
     *
     * @param authorId tópico opcional: {@code null} = todos os autores; preenchido = só aquele autor
     */
    public Multi<PostView> subscribe(String authorId) {
        return Multi.createFrom()
                .publisher(FlowAdapters.toFlowPublisher(
                        queryGateway.subscriptionQuery(new OnPostCreated(authorId), PostView.class)))
                .onOverflow().buffer(UPDATE_BUFFER);
    }
}
