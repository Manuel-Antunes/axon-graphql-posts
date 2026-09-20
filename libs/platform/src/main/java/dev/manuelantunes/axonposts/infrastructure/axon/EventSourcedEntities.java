package dev.manuelantunes.axonposts.infrastructure.axon;

import java.util.HashMap;
import java.util.Map;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;

import at.meks.quarkiverse.axon.runtime.customizations.EventSourcedEntityConfigurer;
import dev.manuelantunes.axonposts.domain.shared.AggregateIdTypes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

@ApplicationScoped
public class EventSourcedEntities implements EventSourcedEntityConfigurer {
    private final Map<Class<?>, Class<?>> idTypes = new HashMap<>();

    EventSourcedEntities(Instance<AggregateIdTypes> declaredByModules) {
        declaredByModules.forEach(module -> idTypes.putAll(module.idTypes()));
    }

    @Override
    public <T> EventSourcedEntityModule<?, T> createConfigurer(Class<T> entity, Class<?> idClass) {
        return EventSourcedEntityModule.autodetected(idTypes.getOrDefault(entity, idClass), entity);
    }
}
