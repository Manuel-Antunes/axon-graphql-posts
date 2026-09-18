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
 * <h2>Só o id do autor, e não o nome</h2>
 * O nome viajava aqui até o {@code PostView} parar de carregar o objeto do autor. Depois disso ele era
 * lido por <b>uma</b> linha do sistema — para montar uma referência cujo nome ninguém consultava. Um
 * campo desnormalizado num contrato imutável só se justifica enquanto alguém o lê; quando para, ele vira
 * uma promessa que o event store carrega para sempre sem ninguém cobrar.
 * <p>
 * Quem mostra o autor no GraphQL é o DataLoader, que carrega a entidade de verdade e sempre com o nome
 * atual — que é o comportamento certo para um perfil, ao contrário de uma tag, onde a cópia histórica faz
 * sentido.
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
        Instant occurredAt
) implements DomainEvent {
}
