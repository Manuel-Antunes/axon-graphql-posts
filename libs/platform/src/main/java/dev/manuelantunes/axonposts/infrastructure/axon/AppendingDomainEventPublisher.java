package dev.manuelantunes.axonposts.infrastructure.axon;

import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

import java.util.Objects;

/**
 * Adapter que liga a porta {@link DomainEventPublisher} (do domínio) ao {@code EventAppender} (do Axon).
 * <p>
 * É aqui que "o domínio disparou um evento" vira "o evento entrou na transação". O {@code EventAppender}
 * chega como parâmetro do {@code @CommandHandler} já ligado ao {@code ProcessingContext} do command, então
 * o append é transacional: o evento só vai para o event store — e só chega nos event handlers — no commit.
 * <p>
 * Este adapter mora na <b>aplicação</b>, e não na infraestrutura, porque só existe dentro do command
 * handler: ele não é um bean, é um invólucro de vida curta criado por chamada.
 */
public final class AppendingDomainEventPublisher implements DomainEventPublisher {

    private final EventAppender appender;

    private AppendingDomainEventPublisher(EventAppender appender) {
        this.appender = Objects.requireNonNull(appender, "appender");
    }

    /** Construtor nomeado: lê como "publisher que apenda no {@code appender}". */
    public static DomainEventPublisher appendingTo(EventAppender appender) {
        return new AppendingDomainEventPublisher(appender);
    }

    @Override
    public void raise(DomainEvent event) {
        appender.append(event);
    }
}
