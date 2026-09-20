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

@ApplicationScoped
public class FindUsersByIdsQuery {
    @Query(namespace = "users", name = "FindUsersByIds", version = "1.0.0")
    public record FindUsersByIds(List<String> userIds) {
    }

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
