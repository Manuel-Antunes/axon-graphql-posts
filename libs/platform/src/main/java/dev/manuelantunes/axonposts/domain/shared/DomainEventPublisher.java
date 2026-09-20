package dev.manuelantunes.axonposts.domain.shared;

@FunctionalInterface
public interface DomainEventPublisher {
    void raise(DomainEvent event);
}
