package dev.manuelantunes.axonposts.infrastructure.axon;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;

import at.meks.quarkiverse.axon.runtime.customizations.EventSourcedEntityConfigurer;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class EventSourcedEntities implements EventSourcedEntityConfigurer {
    @Override
    public <T> EventSourcedEntityModule<?, T> createConfigurer(Class<T> entity, Class<?> idClass) {
        return EventSourcedEntityModule.autodetected(EntityIdType.of(entity).orElse(idClass), entity);
    }
}
