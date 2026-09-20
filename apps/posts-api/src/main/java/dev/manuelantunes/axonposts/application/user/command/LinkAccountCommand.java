package dev.manuelantunes.axonposts.application.user.command;

import dev.manuelantunes.axonposts.domain.user.AuthProvider;
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

@ApplicationScoped
public class LinkAccountCommand {
    @Command(namespace = "users", name = "LinkAccount", version = "1.0.0")
    public record LinkAccount(
            @TargetEntityId UserId userId,
            AuthProvider provider,
            String subject
    ) {
    }

    private final Clock clock;
    private final UserRepository users;

    public LinkAccountCommand(Clock clock, UserRepository users) {
        this.clock = clock;
        this.users = users;
    }

    @CommandHandler
    public void handle(LinkAccount command,
                       @InjectEntity User user,
                       EventAppender eventAppender) {
        user.link(command.provider(), command.subject(), clock.instant(), appendingTo(eventAppender));

        users.save(user);
    }
}
