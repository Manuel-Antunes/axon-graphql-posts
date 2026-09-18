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

import static dev.manuelantunes.axonposts.application.shared.AppendingDomainEventPublisher.appendingTo;

/**
 * O command <b>PromoteToAuthor</b>: encerra o agregado do leitor em favor de um novo.
 *
 * <h2>Por que promover não é um comando só</h2>
 * O tipo concreto de uma entidade polimórfica é fixado pelo primeiro evento do stream — a documentação do
 * Axon é explícita, e é a consequência inevitável de o {@code @EntityCreator} decidir por leitura do
 * histórico. Não há evento capaz de transformar um {@code Reader} num {@code Author}.
 * <p>
 * Então a promoção é uma <b>sequência</b>, orquestrada pelo {@code UserProvisioning}:
 * <ol>
 *   <li>este command encerra o leitor ({@code UserSupersededEvent}) e libera as credenciais dele;</li>
 *   <li>um {@code RegisterUser} com {@code author = true} abre o stream do autor;</li>
 *   <li>um {@code LinkAccount} por credencial a religa no agregado novo.</li>
 * </ol>
 * Este handler faz só o primeiro passo. Ele é deliberadamente burro sobre o resto: quem sabe que existe
 * uma promoção em curso é a aplicação, não o domínio do usuário.
 */
@ApplicationScoped
public class PromoteToAuthorCommand {

    /**
     * @param successorId id do agregado que vai substituir este. Gerado por quem despacha, para que a
     *                    sequência inteira já saiba o id final antes do primeiro passo
     */
    @Command(namespace = "users", name = "PromoteToAuthor", version = "1.0.0")
    public record PromoteToAuthor(
            @TargetEntityId UserId userId,
            UserId successorId
    ) {
    }

    private final Clock clock;
    private final UserRepository users;

    public PromoteToAuthorCommand(Clock clock, UserRepository users) {
        this.clock = clock;
        this.users = users;
    }

    @CommandHandler
    public void handle(PromoteToAuthor command,
                       @InjectEntity User user,
                       EventAppender eventAppender) {
        User superseded = user.supersede(command.successorId(), clock.instant(), appendingTo(eventAppender));

        users.save(superseded);
    }
}
