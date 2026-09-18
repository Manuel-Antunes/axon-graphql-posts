package dev.manuelantunes.axonposts.domain.user.event;

import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;

/**
 * Evento de domínio: um usuário passou a existir. <b>Primeiro</b> evento do stream, sempre.
 *
 * <h2>É este payload que decide o tipo concreto</h2>
 * O polimorfismo de entidade do Axon resolve a subclasse a partir do primeiro evento: o
 * {@code @EntityCreator} de {@code User} lê {@link #author()} e devolve um {@code Author} ou um
 * {@code Reader}. Nenhuma outra coisa no sistema escolhe esse tipo — nem uma coluna, nem uma flag, nem o
 * token.
 * <p>
 * A consequência é a regra que a documentação do Axon enuncia: <i>o tipo é fixo na criação e não muda em
 * runtime</i>. Um leitor que ganhe a role de autor no Keycloak não vira {@code Author} neste stream;
 * ganha um stream novo, e este é encerrado por {@link UserSupersededEvent}.
 *
 * @param supersedes usuário que este substitui numa promoção, ou {@code null} num cadastro comum. Existe
 *                   para o histórico não se perder: dá para recuperar quem era o leitor antes de virar
 *                   autor sem consultar nada além do stream
 * @param bio        só faz sentido para autor; ignorado quando {@code author} é falso
 */
@Event(namespace = "users", name = "UserRegistered", version = "1.0.0")
public record UserRegisteredEvent(
        @EventTag UserId userId,
        String email,
        String name,
        boolean author,
        String bio,
        UserId supersedes,
        Instant occurredAt
) implements DomainEvent {

    /** Cadastro comum: ninguém está sendo substituído. */
    public static UserRegisteredEvent reader(UserId userId, String email, String name, Instant occurredAt) {
        return new UserRegisteredEvent(userId, email, name, false, null, null, occurredAt);
    }

    public static UserRegisteredEvent author(UserId userId, String email, String name, String bio, Instant occurredAt) {
        return new UserRegisteredEvent(userId, email, name, true, bio, null, occurredAt);
    }
}
