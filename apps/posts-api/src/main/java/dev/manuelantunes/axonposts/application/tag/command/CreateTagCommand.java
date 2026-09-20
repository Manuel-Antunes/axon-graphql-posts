package dev.manuelantunes.axonposts.application.tag.command;

import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.exception.TagAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.util.Optional;

import static dev.manuelantunes.axonposts.infrastructure.axon.AppendingDomainEventPublisher.appendingTo;

@ApplicationScoped
public class CreateTagCommand {
    @Command(namespace = "tags", name = "CreateTag", version = "1.0.0")
    public record CreateTag(
            @TargetEntityId TagId tagId,
            String name
    ) {
    }

    private final Clock clock;
    private final TagRepository tags;

    public CreateTagCommand(Clock clock, TagRepository tags) {
        this.clock = clock;
        this.tags = tags;
    }

    @CommandHandler
    public TagId handle(CreateTag command,
                        @InjectEntity Optional<Tag> existing,
                        EventAppender eventAppender) {
        if (existing.isPresent()) {
            throw new TagAlreadyExistsException(command.tagId());
        }

        Tag tag = Tag.create(command.tagId(), command.name(), clock.instant(), appendingTo(eventAppender));

        tags.save(tag);
        return tag.id();
    }
}
