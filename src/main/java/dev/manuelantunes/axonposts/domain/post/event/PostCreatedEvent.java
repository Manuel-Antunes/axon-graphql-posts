package dev.manuelantunes.axonposts.domain.post.event;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

/**
 * Evento de domínio: um Post passou a existir. Disparado por {@code Post.create(...)}.
 *
 * <h2>Duas tags, dois eixos de leitura</h2>
 * {@code @EventTag} no {@code postId} é o que liga o evento ao stream da entidade no Axon 5 (dynamic
 * consistency boundary): o {@code Post} é reidratado com {@code EventCriteria.havingTags(postId=<id>)}.
 * <p>
 * {@code @EventTag} no {@code authorId} não serve para reidratar nada — serve para <b>consultar</b>. Com
 * ele, "todos os eventos do autor X" é um critério de event store, e não uma varredura: é a base da
 * newsletter, e o que permitiria um dia reconstruir uma projeção por autor a partir do zero. É de graça
 * porque o event store indexa tags no append; a diferença é só declarar a intenção agora, enquanto os
 * eventos ainda não foram escritos.
 *
 * <h2>Por que o nome do autor viaja junto do id</h2>
 * Mesma razão do {@code PostUpdatedEvent.Tag}: o replay reconstrói o {@code Post} sem sessão JPA e
 * precisa montar um {@code Author.reference(id, nome)}. Sem o nome, ou o replay iria ao banco — e
 * deixaria de ser função pura do stream — ou o post reconstituído não saberia dizer quem o escreveu.
 * <p>
 * O payload é de tipos primitivos e value objects de identidade, não de entidades: um evento é
 * <b>contrato</b> — atravessa processo, é serializado e fica gravado para sempre.
 */
@Event(namespace = "posts", name = "PostCreated", version = "1.0.0")
public record PostCreatedEvent(
        @EventTag PostId postId,
        String title,
        String content,
        @EventTag UserId authorId,
        String authorName,
        Instant occurredAt
) implements DomainEvent {
}
