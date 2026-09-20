package dev.manuelantunes.axonposts.application.post.projection;

import java.util.List;
import java.util.Optional;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.event.TagCreatedEvent;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PostCreatedProjection {
    private static final Logger log = LoggerFactory.getLogger(PostCreatedProjection.class);

    private final PostRepository posts;
    private final TagRepository tags;

    public PostCreatedProjection(PostRepository posts, TagRepository tags) {
        this.posts = posts;
        this.tags = tags;
    }

    @EventHandler
    public void on(PostCreatedEvent event) {
        Post post = posts.findById(event.postId())
                .orElseThrow(() -> new IllegalStateException(
                        "PostCreated de um Post que não está no banco: " + event.postId()
                                + " — só quem criou o post materializa a linha dele"));

        if (post.version().value() < event.version()) {
            materialize(post, event);
        }
    }

    private void materialize(Post post, PostCreatedEvent event) {
        log.info("post {} veio completo de outro serviço (v{} contra v{} no banco) — materializando",
                event.postId(), event.version(), post.version());

        List<Tag> managed = event.tags().stream()
                .map(assigned -> materializeTag(assigned, event))
                .toList();

        post.materializeCompletion(event, managed);
        posts.save(post);
    }

    private Tag materializeTag(PostCreatedEvent.AssignedTag assigned, PostCreatedEvent event) {
        TagId tagId = TagId.of(assigned.tagId());
        Optional<Tag> existing = tags.findById(tagId);
        if (existing.isPresent()) {
            return existing.get();
        }

        log.debug("a tag {} ({}) não existe neste serviço — projetando a linha", assigned.name(), tagId);
        tags.saveIfAbsent(new Tag(new TagCreatedEvent(tagId, assigned.name(), event.occurredAt())));

        return tags.findById(tagId).orElseThrow(() -> new IllegalStateException(
                "a tag " + tagId + " foi gravada e não foi encontrada em seguida"));
    }
}
