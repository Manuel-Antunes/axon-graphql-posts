package dev.manuelantunes.axonposts.domain.post.event;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

/**
 * Evento de domínio: um Post passou a existir. Disparado por {@code Post.create(...)}.
 * <p>
 * {@code @EventTag} no {@code postId} é o que liga o evento ao stream da entidade no Axon 5
 * (dynamic consistency boundary): o event store guarda a tag {@code postId=<valor>} e o {@code Post} é
 * reidratado com {@code EventCriteria.havingTags(postId=<id>)}.
 * <p>
 * O payload é de tipos primitivos, não de value objects: um evento é <b>contrato</b> — atravessa
 * processo, é serializado e fica gravado para sempre. Quem transforma texto em {@code PostTitle} /
 * {@code PostContent} / {@code Author} é a entidade, na fronteira ({@code Post.createdFrom}).
 * <p>
 * Carrega o estado inicial completo, então a projeção e a subscription {@code onPostCreated} montam a
 * view sem consultar nada.
 */
@Event(namespace = "posts", name = "PostCreated", version = "1.0.0")
public record PostCreatedEvent(
        @EventTag PostId postId,
        String title,
        String content,
        String author,
        Instant occurredAt
) implements DomainEvent {
}
