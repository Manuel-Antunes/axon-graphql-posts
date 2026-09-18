package dev.manuelantunes.axonposts.application.user.command;

import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;

import static dev.manuelantunes.axonposts.infrastructure.axon.AppendingDomainEventPublisher.appendingTo;

/**
 * O command <b>RestoreUser</b>: reativa uma conta apagada.
 *
 * <h2>De novo, o event sourcing resolve a galinha e o ovo</h2>
 * Um usuário apagado é invisível para o JPA — o {@code @SQLRestriction} o esconde de toda consulta. Para
 * restaurar seria preciso carregar, e para carregar seria preciso não estar apagado.
 * <p>
 * Aqui não é: o {@code @InjectEntity User} <b>não vem do banco</b>, vem do stream, que nenhuma cláusula
 * SQL filtra. É exatamente o mesmo argumento do {@code RestorePostCommand} — e a repetição é o ponto: a
 * propriedade vale para qualquer agregado event-sourced com exclusão lógica.
 *
 * <h2>As duas escritas, e a ordem</h2>
 * <ol>
 *   <li>{@code users.restore(id)} — UPDATE nativo que zera o {@code deleted_at} e faz a linha voltar a
 *       existir para o JPA;</li>
 *   <li>{@code users.save(user)} — agora o {@code merge} acha a linha.</li>
 * </ol>
 * Invertida, o merge não encontraria a linha escondida, concluiria que a entidade é nova e tentaria um
 * INSERT com chave primária repetida.
 */
@ApplicationScoped
public class RestoreUserCommand {

    @Command(namespace = "users", name = "RestoreUser", version = "1.0.0")
    public record RestoreUser(@TargetEntityId UserId userId) {
    }

    private final Clock clock;
    private final UserRepository users;

    public RestoreUserCommand(Clock clock, UserRepository users) {
        this.clock = clock;
        this.users = users;
    }

    @CommandHandler
    public void handle(RestoreUser command,
                       @InjectEntity User user,
                       EventAppender eventAppender) {
        User restored = user.restore(clock.instant(), appendingTo(eventAppender));

        users.restore(restored.id());
        users.save(restored);
    }
}
