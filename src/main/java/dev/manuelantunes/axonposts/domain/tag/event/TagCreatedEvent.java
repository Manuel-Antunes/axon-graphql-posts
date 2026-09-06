package dev.manuelantunes.axonposts.domain.tag.event;

import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

/**
 * Evento de domínio: uma Tag passou a existir. O {@code @EventTag} no {@code tagId} dá à Tag o seu
 * próprio stream, separado do stream de qualquer Post — são agregados independentes.
 */
@Event(namespace = "tags", name = "TagCreated", version = "1.0.0")
public record TagCreatedEvent(
        @EventTag TagId tagId,
        String name,
        Instant occurredAt
) implements DomainEvent {
}
