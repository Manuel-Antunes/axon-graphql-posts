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
