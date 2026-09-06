package dev.manuelantunes.axonposts.domain.post.event;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

/**
 * Evento de domínio: título e/ou conteúdo de um Post mudaram. Disparado por {@code post.update(...)}.
 * <p>
 * {@code title} e {@code content} são os valores <b>resultantes</b> (o estado depois da mudança), não só
 * o delta — assim a projeção só sobrescreve, e o payload de {@code onPostUpdated} já sai completo.
 * O autor não aparece porque não muda.
 */
@Event(namespace = "posts", name = "PostUpdated", version = "1.0.0")
public record PostUpdatedEvent(
        @EventTag PostId postId,
        String title,
        String content,
        Instant occurredAt
) implements DomainEvent {
}
