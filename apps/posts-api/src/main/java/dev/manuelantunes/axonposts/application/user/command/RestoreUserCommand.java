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
