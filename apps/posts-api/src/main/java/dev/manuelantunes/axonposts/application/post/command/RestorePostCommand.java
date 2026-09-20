package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.user.Author;
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
public class RestorePostCommand {
    @Command(namespace = "posts", name = "RestorePost", version = "1.0.0")
    public record RestorePost(@TargetEntityId PostId postId, UserId actingAuthor) {
    }

    private final Clock clock;
    private final PostRepository posts;

    public RestorePostCommand(Clock clock, PostRepository posts) {
        this.clock = clock;
        this.posts = posts;
    }

    @CommandHandler
    public void handle(RestorePost command,
                       @InjectEntity Post post,
                       EventAppender eventAppender) {
        Post restored = post.restore(
                Author.reference(command.actingAuthor()), clock.instant(), appendingTo(eventAppender));

        posts.restore(restored.id());
        posts.save(restored);
    }
}
