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
 * A subscription <b>OnPostUpdated</b>: a mensagem {@link OnPostUpdated} e as duas pontas dela. Mesma
 * mecânica da {@link OnPostCreatedSubscription}, com um tópico opcional por {@code postId}.
 */
@ApplicationScoped
public class OnPostUpdatedSubscription {

    /**
     * A mensagem: "me avise quando um Post for atualizado".
     * <p>
     * Os dois filtros são independentes e combinam por <b>E</b>: com os dois preenchidos, o assinante
     * recebe um post específico e só enquanto ele for daquele autor. O filtro é avaliado no emit, pelo
     * {@code PostUpdatedEventHandler}.
     *
     * @param postId   tópico opcional — {@code null} recebe update de qualquer Post; preenchido, só os
     *                 daquele id
     * @param authorId tópico opcional — {@code null} recebe de qualquer autor; preenchido, só os daquele
     *                 autor. É a outra metade da newsletter: {@code onPostCreated} traz o que ele
     *                 publica, {@code onPostUpdated} traz o que ele edita — inclusive a atribuição da
     *                 tag padrão, que também é um PostUpdated
     */
    @Query(namespace = "posts", name = "OnPostUpdated", version = "1.0.0")
    public record OnPostUpdated(String postId, String authorId) {

        /** O predicado do tópico mora junto da mensagem: quem emite não precisa saber a regra. */
        public boolean matches(String updatedPostId, String updatedAuthorId) {
            return matchesTopic(postId, updatedPostId) && matchesTopic(authorId, updatedAuthorId);
        }

        private static boolean matchesTopic(String filter, String actual) {
            return filter == null || filter.isBlank() || filter.equals(actual);
        }
    }

    /**
     * Quantos eventos podem ficar em espera enquanto o assinante não pede o próximo. Ver
     * {@link #subscribe} sobre por que este buffer é obrigatório e não um afinamento.
     */
    private static final int UPDATE_BUFFER = 256;

    private final QueryGateway queryGateway;

    public OnPostUpdatedSubscription(QueryGateway queryGateway) {
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
     * Stream de edições a partir de agora.
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
     * @param postId   tópico opcional: {@code null} = todos os posts
     * @param authorId tópico opcional: {@code null} = todos os autores. Os dois combinam com AND
     */
    public Multi<PostView> subscribe(String postId, String authorId) {
        return Multi.createFrom()
                .publisher(FlowAdapters.toFlowPublisher(
                        queryGateway.subscriptionQuery(new OnPostUpdated(postId, authorId), PostView.class)))
                .onOverflow().buffer(UPDATE_BUFFER);
    }
}
