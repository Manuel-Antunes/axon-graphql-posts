package dev.manuelantunes.axonposts.domain.post.event;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

/**
 * Evento de domínio: um Post apagado voltou. Disparado por {@code Post.restore(...)}.
 * <p>
 * Restaurar é um fato tanto quanto apagar, e é o par deste evento com o {@link PostDeletedEvent} que faz
 * o replay chegar ao estado certo: quem só gravasse a exclusão reconstruiria como apagado um post que
 * voltou.
 *
 * @param version versão resultante do post depois deste evento
 */
@Event(namespace = "posts", name = "PostRestored", version = "1.0.0")
public record PostRestoredEvent(
        @EventTag PostId postId,
        @EventTag UserId authorId,
        long version,
        Instant occurredAt
) implements DomainEvent {
}
