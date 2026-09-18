package dev.manuelantunes.axonposts.domain.user.event;

import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

/**
 * Evento de domínio: este usuário foi substituído por outro, e não recebe mais nada.
 *
 * <h2>Por que a promoção precisa deste evento</h2>
 * O tipo concreto de uma entidade polimórfica do Axon é fixado pelo primeiro evento do stream. Um
 * {@code Reader} não pode virar {@code Author} no mesmo stream — não há evento capaz de mudar a classe
 * que o {@code @EntityCreator} já escolheu.
 * <p>
 * Então promover é <b>encerrar um agregado e abrir outro</b>: este evento fecha o do leitor, e um
 * {@code UserRegisteredEvent} com {@code author = true} e {@code supersedes} preenchido abre o do autor.
 * Os dois streams ficam ligados nos dois sentidos, e a história continua legível.
 *
 * <h2>Por que isso não perde nada</h2>
 * Um {@code Reader} nunca escreveu post — {@code createPost} exige {@code ROLE_AUTHOR}. Não há
 * {@code posts.author_id} apontando para ele, então não há o que remapear: o que passa para o novo
 * usuário são e-mail, nome e as contas ligadas.
 * <p>
 * A mesma promoção partindo de um {@code Author} seria outra história, e é por isso que ela não existe.
 */
@Event(namespace = "users", name = "UserSuperseded", version = "1.0.0")
public record UserSupersededEvent(
        @EventTag UserId userId,
        UserId supersededBy,
        Instant occurredAt
) implements DomainEvent {
}
