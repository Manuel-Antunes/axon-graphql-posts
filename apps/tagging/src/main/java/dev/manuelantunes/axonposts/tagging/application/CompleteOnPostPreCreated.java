package dev.manuelantunes.axonposts.tagging.application;

import java.util.concurrent.CompletableFuture;

import dev.manuelantunes.axonposts.domain.post.event.PostPreCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.tagging.application.CompletePostWithDefaultTagCommand.CompletePostWithDefaultTag;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CompleteOnPostPreCreated {
    private static final Logger log = LoggerFactory.getLogger(CompleteOnPostPreCreated.class);

    private final CommandGateway commandGateway;

    public CompleteOnPostPreCreated(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @EventHandler
    public void on(PostPreCreatedEvent event, ProcessingContext context) {
        log.debug("post {} de {} nasceu sem tag — completando", event.postId(), event.authorId());
        context.onAfterCommit(committed -> complete(event.postId()));
    }

    private CompletableFuture<Void> complete(PostId postId) {
        return commandGateway.send(new CompletePostWithDefaultTag(postId), Void.class)
                .thenRun(() -> {
                });
    }
}
