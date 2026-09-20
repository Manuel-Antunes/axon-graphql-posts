package dev.manuelantunes.axonposts.tagging.application;

import java.time.Clock;
import java.util.List;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import dev.manuelantunes.axonposts.infrastructure.axon.AppendingDomainEventPublisher;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CompletePostWithDefaultTagCommand {
    private static final Logger log = LoggerFactory.getLogger(CompletePostWithDefaultTagCommand.class);

    @Command(namespace = "tagging", name = "CompletePostWithDefaultTag", version = "1.0.0")
    public record CompletePostWithDefaultTag(
            @TargetEntityId PostId postId
    ) {
    }

    private final Clock clock;

    public CompletePostWithDefaultTagCommand(Clock clock) {
        this.clock = clock;
    }

    @CommandHandler
    public void handle(CompletePostWithDefaultTag command,
                       @InjectEntity Post post,
                       EventAppender eventAppender) {
        if (post.isComplete()) {
            log.info("post {} já está completo com {} — entrega duplicada, descartada",
                    command.postId(), post.tags());
            return;
        }

        Tag defaultTag = Tag.reference(Tag.DEFAULT_ID, TagName.of(Tag.DEFAULT_NAME));

        log.debug("post {} recebe a tag padrão {} ({})",
                command.postId(), Tag.DEFAULT_NAME, Tag.DEFAULT_ID);

        post.complete(List.of(defaultTag), clock.instant(),
                AppendingDomainEventPublisher.appendingTo(eventAppender));
    }
}
