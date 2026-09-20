package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.exception.PostAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.exception.TagNotFoundException;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static dev.manuelantunes.axonposts.infrastructure.axon.AppendingDomainEventPublisher.appendingTo;

@ApplicationScoped
public class CreatePostCommand {
    @Command(namespace = "posts", name = "CreatePost", version = "1.0.0")
    public record CreatePost(
            @TargetEntityId PostId postId,
            String title,
            String content,
            UserId authorId,
            List<TagId> tagIds
    ) {
        public CreatePost {
            tagIds = tagIds == null ? List.of() : List.copyOf(tagIds);
        }
    }

    private final Clock clock;
    private final PostRepository posts;
    private final TagRepository tags;

    public CreatePostCommand(Clock clock, PostRepository posts, TagRepository tags) {
        this.clock = clock;
        this.posts = posts;
        this.tags = tags;
    }

    @CommandHandler
    public PostId handle(CreatePost command,
                         @InjectEntity Optional<Post> existing,
                         EventAppender eventAppender) {
        if (existing.isPresent()) {
            throw new PostAlreadyExistsException(command.postId());
        }
        Author author = Author.reference(command.authorId());

        Post post = Post.create(
                command.postId(),
                command.title(),
                command.content(),
                author,
                initialTags(command),
                clock.instant(),
                appendingTo(eventAppender)
        );

        posts.save(post);
        return post.id();
    }

    private List<Tag> initialTags(CreatePost command) {
        return command.tagIds().stream()
                .map(tagId -> tags.findById(tagId).orElseThrow(() -> new TagNotFoundException(tagId)))
                .toList();
    }
}
