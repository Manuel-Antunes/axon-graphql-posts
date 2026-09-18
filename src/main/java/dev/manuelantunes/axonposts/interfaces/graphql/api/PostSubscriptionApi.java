package dev.manuelantunes.axonposts.interfaces.graphql.api;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import dev.manuelantunes.axonposts.application.post.subscription.OnPostCreatedSubscription;
import dev.manuelantunes.axonposts.application.post.subscription.OnPostUpdatedSubscription;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import io.smallrye.graphql.api.Subscription;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Camada de interface das <b>subscriptions</b> GraphQL: devolve o {@link Multi} do handler de aplicação
 * daquela subscription. Quem sabe falar com o {@code QueryBus} é o handler; aqui só se escolhe qual.
 * <p>
 * <h2>Dois transportes, e nenhum deles aparece aqui</h2>
 * O mesmo {@code /graphql} atende os dois, escolhendo pelo cabeçalho da requisição:
 * <ul>
 *   <li>{@code Upgrade: websocket} → {@code graphql-transport-ws} / {@code graphql-ws}, que é o que o
 *       SmallRye serve;</li>
 *   <li>{@code Accept: text/event-stream} → GraphQL over SSE, que o SmallRye <b>não</b> serve e o pacote
 *       {@code interfaces.graphql.sse} acrescenta.</li>
 * </ul>
 * Este resolver não sabe por qual dos dois está sendo servido, e é esse o ponto: um {@code Multi} é um
 * {@code Multi}. Quando o cliente desconecta — fechar o WebSocket ou fechar a conexão HTTP —, o
 * {@code Multi} é cancelado e o Axon fecha a subscription query.
 *
 * <h2>Os tópicos, e onde eles são avaliados</h2>
 * Os argumentos {@code postId}/{@code authorId} viram campos da mensagem de subscription, e o predicado
 * roda no <b>emit</b> — cada event handler pergunta a cada assinante registrado se aquele evento lhe
 * interessa. Ou seja, o filtro é do lado do servidor: um assinante de {@code authorId: X} nunca recebe,
 * nem descarta no cliente, o post de outro autor.
 * <p>
 * O mesmo {@code authorId} está gravado como {@code @EventTag} nos eventos. São coisas separadas com o
 * mesmo valor: a tag serve para <i>consultar o passado</i> no event store, o tópico para <i>filtrar o
 * presente</i> no query bus.
 */
@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class PostSubscriptionApi {

    private final OnPostCreatedSubscription onPostCreated;
    private final OnPostUpdatedSubscription onPostUpdated;

    public PostSubscriptionApi(OnPostCreatedSubscription onPostCreated,
                               OnPostUpdatedSubscription onPostUpdated) {
        this.onPostCreated = onPostCreated;
        this.onPostUpdated = onPostUpdated;
    }

    @Subscription("onPostCreated")
    @NonNull
    @Description("Emite a cada PostCreated. authorId filtra por tópico (null = todos)")
    public Multi<PostView> onPostCreated(@Name("authorId") @Id String authorId) {
        return onPostCreated.subscribe(authorId);
    }

    @Subscription("onPostUpdated")
    @NonNull
    @Description("Emite a cada PostUpdated. Os dois filtros combinam (AND); null em ambos = tudo")
    public Multi<PostView> onPostUpdated(@Name("postId") @Id String postId,
                                         @Name("authorId") @Id String authorId) {
        return onPostUpdated.subscribe(postId, authorId);
    }
}
