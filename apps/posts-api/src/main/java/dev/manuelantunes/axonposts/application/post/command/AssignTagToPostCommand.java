package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.exception.TagNotFoundException;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;

import static dev.manuelantunes.axonposts.infrastructure.axon.AppendingDomainEventPublisher.appendingTo;

@ApplicationScoped
public class AssignTagToPostCommand {
    @Command(namespace = "posts", name = "AssignTagToPost", version = "1.0.0")
    public record AssignTagToPost(
            @TargetEntityId PostId postId,
            TagId tagId
    ) {
    }

    private final Clock clock;
    private final PostRepository posts;
    private final TagRepository tags;

    public AssignTagToPostCommand(Clock clock, PostRepository posts, TagRepository tags) {
        this.clock = clock;
        this.posts = posts;
        this.tags = tags;
    }

    @CommandHandler
    public void handle(AssignTagToPost command,
                       @InjectEntity Post post,
                       EventAppender eventAppender) {
        Tag tag = tags.findById(command.tagId())
                .orElseThrow(() -> new TagNotFoundException(command.tagId()));

        Post tagged = post.assignTag(tag, clock.instant(), appendingTo(eventAppender));

        posts.save(tagged);
    }
}
