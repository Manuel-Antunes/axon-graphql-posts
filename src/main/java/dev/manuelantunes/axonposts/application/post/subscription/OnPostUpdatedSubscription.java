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

    private final ReactorQueryGateway queryGateway;

    // o gateway vem do registry de componentes do Axon, não de um @Bean: a inspeção do IDE não o vê
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
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
     * @param postId   tópico opcional: {@code null} = todos os Posts; preenchido = só aquele Post
     * @param authorId tópico opcional: {@code null} = todos os autores; preenchido = só aquele autor
     */
    public Flux<PostView> subscribe(String postId, String authorId) {
        return queryGateway.subscriptionQuery(new OnPostUpdated(postId, authorId), PostView.class);
    }
}
