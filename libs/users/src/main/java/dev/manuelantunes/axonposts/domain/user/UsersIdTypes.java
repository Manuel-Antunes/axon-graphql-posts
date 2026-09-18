package dev.manuelantunes.axonposts.domain.user;

import java.util.Map;

import dev.manuelantunes.axonposts.domain.shared.AggregateIdTypes;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Os tipos de id que <b>este módulo</b> declara ao Axon.
 * <p>
 * Só {@code User} aparece aqui, e {@code Reader}/{@code Author} não: o agregado é polimórfico e o Axon
 * o registra pela raiz — os tipos concretos vêm de {@code @EventSourcedEntity(concreteTypes = ...)} na
 * própria classe.
 */
@ApplicationScoped
public class UsersIdTypes implements AggregateIdTypes {

    @Override
    public Map<Class<?>, Class<?>> idTypes() {
        return Map.of(User.class, UserId.class);
    }
}
