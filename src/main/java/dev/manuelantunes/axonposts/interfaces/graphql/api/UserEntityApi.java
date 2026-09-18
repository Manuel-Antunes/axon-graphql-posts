package dev.manuelantunes.axonposts.interfaces.graphql.api;

import java.util.List;
import java.util.Map;

import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.jboss.logging.Logger;

import dev.manuelantunes.axonposts.application.user.query.FindUsersByIdsQuery.FindUsersByIds;
import dev.manuelantunes.axonposts.application.user.query.FindUsersByIdsQuery.UsersById;
import dev.manuelantunes.axonposts.application.user.view.AuthorView;
import dev.manuelantunes.axonposts.application.user.view.ReaderView;
import dev.manuelantunes.axonposts.application.user.view.UserView;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import io.smallrye.graphql.api.federation.Resolver;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Os resolvedores de referência do agregado de usuário — três, e não um, porque o casamento é por
 * <b>tipo de retorno</b>.
 *
 * <h2>Por que três métodos para uma consulta só</h2>
 * O {@code FederationDataFetcher} escolhe o resolvedor comparando o {@code __typename} da representação
 * com o nome do tipo devolvido pelo campo. Uma representação de {@code Author} procura um campo que
 * devolva {@code [Author]}; um método que devolvesse {@code [User]} não casa, mesmo que o objeto que
 * saísse dele fosse um {@code Author}. Java não ajuda aqui: {@code List<UserView>} e
 * {@code List<AuthorView>} são o mesmo apagamento, mas viram tipos GraphQL diferentes — que é
 * exatamente o que o casamento usa.
 * <p>
 * Os três dividem o mesmo {@code FindUsersByIds} e o mesmo lote. O que muda é só o recorte na saída.
 *
 * <h2>{@code User} como interface de entidade</h2>
 * A representação com {@code __typename: "User"} chega quando outro subgraph declara
 * {@code @interfaceObject} — a forma da Federação 2.3 de acrescentar um campo a <b>todas</b> as
 * implementações sem conhecer nenhuma. Quem escreve esse subgraph vê um tipo só; quem resolve aqui
 * devolve o concreto, e o {@code resolveEntityType} do SmallRye repõe o {@code __typename} certo a
 * partir do {@code @Name} da classe que voltou.
 * <p>
 * É o que torna o {@code @Key} na {@code interface User} verdadeiro. Sem este método ele seria uma
 * promessa que a composição aceita e o runtime quebra — o mesmo defeito que o {@link TagEntityApi}
 * conserta para a tag.
 *
 * <h2>Pedir o tipo errado responde {@code null}, não o outro tipo</h2>
 * {@code author(...)} filtra por {@code instanceof}: o id de um leitor devolve {@code null} naquela
 * posição. Responder o {@code Reader} seria pior do que não responder — o roteador anexaria campos de
 * {@code Author} a um objeto que não é um, e a resposta sairia inconsistente sem ninguém errar.
 * Entidade que sumiu e entidade que nunca foi daquele tipo têm, aqui, a mesma resposta correta.
 */
@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class UserEntityApi {

    private static final Logger log = Logger.getLogger(UserEntityApi.class);

    private final QueryGateway queryGateway;

    public UserEntityApi(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    @Resolver
    public Uni<List<UserView>> user(@Name("id") List<String> id) {
        return byId(id).map(byId -> id.stream().map(byId::get).toList());
    }

    @Resolver
    public Uni<List<AuthorView>> author(@Name("id") List<String> id) {
        return byId(id).map(byId -> id.stream().map(userId -> as(byId, userId, AuthorView.class)).toList());
    }

    @Resolver
    public Uni<List<ReaderView>> reader(@Name("id") List<String> id) {
        return byId(id).map(byId -> id.stream().map(userId -> as(byId, userId, ReaderView.class)).toList());
    }

    private Uni<Map<String, UserView>> byId(List<String> ids) {
        log.debugf("_entities: lote de %d usuário(s) numa consulta", ids.size());
        return Uni.createFrom()
                .completionStage(() -> queryGateway.query(new FindUsersByIds(ids), UsersById.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(UsersById::byId);
    }

    private static <T extends UserView> T as(Map<String, UserView> byId, String userId, Class<T> type) {
        UserView user = byId.get(userId);
        return type.isInstance(user) ? type.cast(user) : null;
    }
}
