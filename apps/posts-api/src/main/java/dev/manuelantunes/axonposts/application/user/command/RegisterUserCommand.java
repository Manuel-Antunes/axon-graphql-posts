package dev.manuelantunes.axonposts.application.user.command;

import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.exception.UserAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.util.Optional;

import static dev.manuelantunes.axonposts.infrastructure.axon.AppendingDomainEventPublisher.appendingTo;

@ApplicationScoped
public class RegisterUserCommand {
    @Command(namespace = "users", name = "RegisterUser", version = "1.0.0")
    public record RegisterUser(
            @TargetEntityId UserId userId,
            String email,
            String name,
            boolean author,
            String bio,
            UserId supersedes
    ) {
        public static RegisterUser reader(UserId userId, String email, String name) {
            return new RegisterUser(userId, email, name, false, null, null);
        }

        public static RegisterUser author(UserId userId, String email, String name) {
            return new RegisterUser(userId, email, name, true, null, null);
        }
    }

    private final Clock clock;
    private final UserRepository users;

    public RegisterUserCommand(Clock clock, UserRepository users) {
        this.clock = clock;
        this.users = users;
    }

    @CommandHandler
    public UserId handle(RegisterUser command,
                         @InjectEntity Optional<User> existing,
                         EventAppender eventAppender) {
        if (existing.isPresent()) {
            throw new UserAlreadyExistsException(command.userId());
        }

        User user = User.register(
                command.userId(),
                command.email(),
                command.name(),
                command.author(),
                command.bio(),
                command.supersedes(),
                clock.instant(),
                appendingTo(eventAppender)
        );

        users.save(user);
        return user.id();
    }
}
