package dev.manuelantunes.axonposts.domain.post.event;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

/**
 * Evento de domínio: um Post <b>passou a existir</b>, e ainda não está completo.
 *
 * <h2>Por que o ciclo de vida do Post tem duas fases</h2>
 * Porque a primeira tag deixou de ser decidida aqui. A decisão "qual tag este post recebe" é de OUTRO
 * serviço, e a mensagem que a carrega atravessa um broker — ou seja, o post existe por um tempo em que
 * ninguém ainda sabe a tag dele. Fingir que criar e publicar são o mesmo instante exigiria esperar o
 * serviço vizinho dentro da transação de escrita, o que amarraria o {@code createPost} à saúde dele.
 * <p>
 * Então {@code PostPreCreated} afirma o fato menor e verdadeiro — o post existe, tem autor, título e
 * conteúdo — e {@link PostCreatedEvent} afirma o fato maior: está completo e visível. O post nasce na
 * versão 1 e chega à 2 quando a tag volta.
 *
 * <h2>Este é o ÚNICO criador do agregado</h2>
 * Inclusive quando o post já nasce com tags. Nesse caso os dois eventos são apendados na mesma unidade
 * de trabalho, e o post vai da versão 1 para a 2 sem nenhuma mensagem atravessar o broker. Ter um só
 * {@code @EntityCreator} não é economia de código: é o que faz "de onde vem um Post" ter uma resposta
 * única, em vez de duas que precisam concordar para sempre.
 *
 * <h2>É este o evento que sai para os outros serviços</h2>
 * A routing key dele é {@code posts.PostPreCreated.<postId>}, e é nela que o serviço de tagueamento se
 * vincula. Nada neste arquivo sabe disso — a chave é derivada da anotação {@code @Event} e da
 * {@code @EventTag}, que existem por razões de domínio. É a propriedade que faz a coreografia não
 * precisar que um serviço conheça o outro.
 */
@Event(namespace = "posts", name = "PostPreCreated", version = "1.0.0")
public record PostPreCreatedEvent(
        @EventTag PostId postId,
        String title,
        String content,
        /*
         * SEM @EventTag, e a razão está em PostCreatedEvent: o event store JPA do Axon 5.3.1 é
         * aggregate mode e aceita UMA tag por evento.
         */
        UserId authorId,
        Instant occurredAt
) implements DomainEvent {
}
