package dev.manuelantunes.axonposts.application.post.projection;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;

import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PostUpdatedProjection {
    private final PostRepository posts;

    public PostUpdatedProjection(PostRepository posts) {
        this.posts = posts;
    }

    @EventHandler
    public void on(PostUpdatedEvent event) {
        posts.findById(event.postId())
                .orElseThrow(() -> new IllegalStateException(
                        "PostUpdated de um Post que não está no banco: " + event.postId()
                                + " — o command deveria tê-lo salvo antes do commit"));
    }
}
