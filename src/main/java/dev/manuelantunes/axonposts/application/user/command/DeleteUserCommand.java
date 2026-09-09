package dev.manuelantunes.axonposts.application.user.command;

import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.springframework.stereotype.Component;

import java.time.Clock;

import static dev.manuelantunes.axonposts.application.shared.AppendingDomainEventPublisher.appendingTo;

/**
 * O command <b>DeleteUser</b>: exclusão lógica da conta, pedida pelo próprio dono.
 * <p>
 * O {@code save} é um {@code merge} comum: no momento em que ele roda a linha ainda está visível (o
 * {@code deleted_at} só existe no objeto em memória), então o UPDATE a encontra e a esconde. É o caminho
 * de volta que precisa de tratamento especial — ver {@link RestoreUserCommand}.
 * <p>
 * <h2>Os posts do autor somem junto — e isso é consequência, não escolha isolada</h2>
 * Os posts não têm {@code deleted_at} próprio e ninguém os toca aqui. Mesmo assim eles desaparecem das
 * consultas, porque {@code Post.author} é {@code @ManyToOne(optional = false)} e o {@code @SQLRestriction}
 * do {@code User} filtra o lado de lá: o join vira <b>INNER</b> contra uma linha que o filtro esconde, e o
 * post não passa.
 * <p>
 * É coerente com o que a operação significa — quem apagou a conta apagou a presença — e é reversível:
 * reativar o usuário faz os posts reaparecerem, inteiros, sem nada ter sido reescrito. O teste
 * {@code deletingTheAccountHidesItAndLoggingInAgainBringsItBack} guarda as duas metades.
 * <p>
 * Manter os posts publicados com o autor apagado seria a outra decisão possível, e exigiria trabalho
 * explícito: tornar a associação opcional e o join externo, e decidir o que mostrar no lugar do autor.
 */
@Component
public class DeleteUserCommand {

    @Command(namespace = "users", name = "DeleteUser", version = "1.0.0")
    public record DeleteUser(@TargetEntityId UserId userId) {
    }

    private final Clock clock;
    private final UserRepository users;

    public DeleteUserCommand(Clock clock, UserRepository users) {
        this.clock = clock;
        this.users = users;
    }

    @CommandHandler
    public void handle(DeleteUser command,
                       @InjectEntity User user,
                       EventAppender eventAppender) {
        User deleted = user.delete(clock.instant(), appendingTo(eventAppender));

        users.save(deleted);
    }
}
