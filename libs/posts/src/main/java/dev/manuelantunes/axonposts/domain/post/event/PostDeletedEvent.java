package dev.manuelantunes.axonposts.domain.post.event;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

/**
 * Evento de domínio: um Post foi apagado (logicamente). Disparado por {@code Post.delete(...)}.
 *
 * <h2>Por que a exclusão lógica precisa de evento aqui, e no {@code User} não</h2>
 * O {@code Post} é event-sourced: o estado dele <b>é</b> o stream. Marcar {@code deleted_at} na linha do
 * Postgres sem apendar um evento criaria um post que some do banco mas continua vivo em qualquer replay —
 * as duas verdades divergiriam na primeira reconstituição.
 * <p>
 * O {@code User} não tem stream: a linha é a verdade inteira, e um {@code users.save(...)} basta.
 *
 * @param version versão resultante do post depois deste evento
 */
@Event(namespace = "posts", name = "PostDeleted", version = "1.0.0")
public record PostDeletedEvent(
        @EventTag PostId postId,
        /*
         * SEM @EventTag, e a razão está em PostCreatedEvent: o event store JPA do Axon 5.3.1 é
         * aggregate mode e aceita UMA tag por evento.
         */
        UserId authorId,
        long version,
        Instant occurredAt
) implements DomainEvent {
}
