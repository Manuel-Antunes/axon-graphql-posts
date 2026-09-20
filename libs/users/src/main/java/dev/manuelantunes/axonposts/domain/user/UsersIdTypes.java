package dev.manuelantunes.axonposts.domain.user;

import java.util.Map;

import dev.manuelantunes.axonposts.domain.shared.AggregateIdTypes;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class UsersIdTypes implements AggregateIdTypes {
    @Override
    public Map<Class<?>, Class<?>> idTypes() {
        return Map.of(User.class, UserId.class);
    }
}
