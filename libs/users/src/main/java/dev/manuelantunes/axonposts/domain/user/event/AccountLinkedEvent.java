package dev.manuelantunes.axonposts.domain.user.event;

import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

/**
 * Evento de domínio: uma credencial foi ligada a um usuário — o <i>account linking</i>.
 * <p>
 * O {@code accountId} vem no evento e não é sorteado na reidratação: o replay tem de reconstruir a mesma
 * {@code Account}, com a mesma identidade, todas as vezes. Um {@code UUID.randomUUID()} dentro de um
 * {@code @EventSourcingHandler} daria uma conta diferente a cada reconstituição.
 * <p>
 * {@code passwordHash} viaja porque uma conta {@code CREDENTIAL} tem senha e uma federada não — é o
 * "algumas contas têm senha e outras não" atravessando o contrato do evento. Nulo para conta federada.
 */
@Event(namespace = "users", name = "AccountLinked", version = "1.0.0")
public record AccountLinkedEvent(
        @EventTag UserId userId,
        String accountId,
        AuthProvider provider,
        String subject,
        String passwordHash,
        Instant occurredAt
) implements DomainEvent {

    public static AccountLinkedEvent federated(UserId userId, String accountId,
                                               AuthProvider provider, String subject, Instant occurredAt) {
        return new AccountLinkedEvent(userId, accountId, provider, subject, null, occurredAt);
    }
}
