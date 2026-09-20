package dev.manuelantunes.axonposts.application.post.event;

import java.util.Optional;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.manuelantunes.axonposts.application.post.subscription.OnPostCreatedSubscription.OnPostCreated;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.application.post.view.PostViewMapper;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PostCreatedEventHandler {
    private static final Logger log = LoggerFactory.getLogger(PostCreatedEventHandler.class);

    private final PostRepository posts;
    private final PostViewMapper viewMapper;

    public PostCreatedEventHandler(PostRepository posts, PostViewMapper viewMapper) {
        this.posts = posts;
        this.viewMapper = viewMapper;
    }

    @EventHandler
    public void on(PostCreatedEvent event, QueryUpdateEmitter emitter) {
        Optional<PostView> view = posts.findById(event.postId()).map(viewMapper::toView);

        if (view.isEmpty()) {
            log.warn("PostCreated de {} sem linha na projeção — nada a notificar."
                    + " O post foi apagado, ou a projeção ainda não o materializou.", event.postId());
            return;
        }

        PostView post = view.get();
        log.debug("PostCreated {} (v{}) de {} → emitindo para onPostCreated",
                post.id(), post.version(), post.authorId());

        emitter.emit(OnPostCreated.class, subscription -> subscription.matches(post.authorId()), post);
    }
}
