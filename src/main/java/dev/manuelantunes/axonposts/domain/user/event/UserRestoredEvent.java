package dev.manuelantunes.axonposts.domain.user.event;

import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

/**
 * Evento de domínio: uma conta apagada voltou.
 *
 * <h2>Quem produz</h2>
 * O próprio login. Se a pessoa apagou a conta e volta a entrar com a mesma credencial do Keycloak, o
 * {@code UserProvisioning} reativa em vez de criar um segundo usuário — o padrão de "período de graça"
 * que quase todo produto tem, e que aqui sai de graça porque o agregado nunca deixou de existir.
 * <p>
 * O par com o {@link UserDeletedEvent} é o que faz o replay chegar ao lugar certo: quem gravasse só a
 * exclusão reconstruiria como apagado um usuário que voltou.
 */
@Event(namespace = "users", name = "UserRestored", version = "1.0.0")
public record UserRestoredEvent(
        @EventTag UserId userId,
        Instant occurredAt
) implements DomainEvent {
}
