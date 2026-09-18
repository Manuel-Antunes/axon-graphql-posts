package dev.manuelantunes.axonposts.application.user.query;

import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.application.user.view.UserView;
import dev.manuelantunes.axonposts.application.user.view.UserViewMapper;
import org.axonframework.messaging.queryhandling.annotation.Query;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A query <b>FindUsersByIds</b>: vários usuários de uma vez, já no tipo concreto.
 *
 * <h2>Ela substituiu três {@code @BatchMapping}</h2>
 * Antes, {@code email}, {@code bio} e {@code accounts} eram resolvidos por três lotes separados, cada um
 * consultando o banco para preencher <b>um</b> campo. O DTO carregava só id e nome, então tudo o mais
 * exigia voltar ao banco — inclusive quando quem chamava já tinha o usuário inteiro na mão.
 * <p>
 * Agora a consulta é uma e devolve a view completa. O achatamento é polimórfico
 * ({@code UserViewMapper} despacha por {@code instanceof}), e é o Hibernate quem entrega o
 * {@code Author} com a bio pelo join da herança {@code JOINED} — a hidratação que o ORM já fazia e que o
 * desenho anterior jogava fora.
 */
@ApplicationScoped
public class FindUsersByIdsQuery {

    @Query(namespace = "users", name = "FindUsersByIds", version = "1.0.0")
    public record FindUsersByIds(List<String> userIds) {
    }

    /** @param byId id → view completa. Ids que não existem não aparecem */
    public record UsersById(Map<String, UserView> byId) {
    }

    private final UserRepository users;
    private final UserViewMapper viewMapper;

    public FindUsersByIdsQuery(UserRepository users, UserViewMapper viewMapper) {
        this.users = users;
        this.viewMapper = viewMapper;
    }

    @QueryHandler
    public UsersById handle(FindUsersByIds query) {
        if (query.userIds().isEmpty()) {
            return new UsersById(Map.of());
        }

        return new UsersById(users
                .findAllById(query.userIds().stream().map(UserId::of).toList())
                .stream()
                .map(viewMapper::toView)
                .collect(Collectors.toMap(UserView::id, Function.identity(), (first, second) -> first)));
    }
}
