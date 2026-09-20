package dev.manuelantunes.axonposts.application.post.command;

import java.time.Clock;
import java.util.List;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;

import static dev.manuelantunes.axonposts.infrastructure.axon.AppendingDomainEventPublisher.appendingTo;

@ApplicationScoped
public class CompletePostCommand {
    private static final Logger log = LoggerFactory.getLogger(CompletePostCommand.class);

    @Command(namespace = "posts", name = "CompletePost", version = "1.0.0")
    public record CompletePost(
            @TargetEntityId PostId postId,
            TagId tagId
    ) {
    }

    private final Clock clock;
    private final PostRepository posts;
    private final TagRepository tags;

    public CompletePostCommand(Clock clock, PostRepository posts, TagRepository tags) {
        this.clock = clock;
        this.posts = posts;
        this.tags = tags;
    }

    @CommandHandler
    public void handle(CompletePost command,
                       @InjectEntity Post post,
                       EventAppender eventAppender) {
        if (post.isComplete()) {
            log.info("post {} já estava completo — decisão de tagueamento duplicada, descartada",
                    command.postId());
            return;
        }

        Tag tag = tags.findById(command.tagId())
                .orElseThrow(() -> new TagNotFoundException(command.tagId()));

        Post completed = post.complete(List.of(tag), clock.instant(), appendingTo(eventAppender));

        posts.save(completed);
    }
}
