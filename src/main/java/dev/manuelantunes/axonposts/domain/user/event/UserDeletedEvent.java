package dev.manuelantunes.axonposts.domain.user.event;

import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

/**
 * Evento de domínio: um usuário apagou a própria conta (exclusão lógica).
 *
 * <h2>Por que isto virou evento</h2>
 * Enquanto {@code User} era uma entidade JPA comum, marcar {@code deleted_at} na linha bastava — a linha
 * era a verdade inteira. Depois que ele virou agregado event-sourced, não: o estado <b>é</b> o stream, e
 * uma exclusão que não deixasse evento produziria um usuário que some do banco e reaparece vivo em
 * qualquer replay. É a mesma razão que existe para o {@code PostDeletedEvent}.
 * <p>
 * O mixin {@code SoftDeletable} continua dando a mecânica ({@code delete}, {@code restore},
 * {@code isDeleted}); o evento é o que registra o fato. As duas coisas são separadas de propósito — ver o
 * par {@code applyDeletion}/{@code delete} do mixin.
 */
@Event(namespace = "users", name = "UserDeleted", version = "1.0.0")
public record UserDeletedEvent(
        @EventTag UserId userId,
        Instant occurredAt
) implements DomainEvent {
}
