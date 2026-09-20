package dev.manuelantunes.axonposts.application.post.saga;

import java.util.concurrent.CompletableFuture;

import dev.manuelantunes.axonposts.application.post.command.CompletePostCommand.CompletePost;
import dev.manuelantunes.axonposts.domain.post.event.PostPreCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@IfBuildProperty(name = "axonposts.saga.tagging-in-process", stringValue = "true")
public class InProcessTagAssignment {
    private static final Logger log = LoggerFactory.getLogger(InProcessTagAssignment.class);

    private final CommandGateway commandGateway;

    public InProcessTagAssignment(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @EventHandler
    public void on(PostPreCreatedEvent event, ProcessingContext context) {
        context.onAfterCommit(committed -> assignDefaultTag(event.postId()));
    }

    private CompletableFuture<Void> assignDefaultTag(PostId postId) {
        return commandGateway.send(new CompletePost(postId, Tag.DEFAULT_ID), Void.class)
                .thenRun(() -> log.debug("post {} completado em processo com a tag {}",
                        postId, Tag.DEFAULT_NAME));
    }
}
