package dev.manuelantunes.axonposts.infrastructure.axon;

import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

import java.util.Objects;

public final class AppendingDomainEventPublisher implements DomainEventPublisher {
    private final EventAppender appender;

    private AppendingDomainEventPublisher(EventAppender appender) {
        this.appender = Objects.requireNonNull(appender, "appender");
    }

    public static DomainEventPublisher appendingTo(EventAppender appender) {
        return new AppendingDomainEventPublisher(appender);
    }

    @Override
    public void raise(DomainEvent event) {
        appender.append(event);
    }
}
