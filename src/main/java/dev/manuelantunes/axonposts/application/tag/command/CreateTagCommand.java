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
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

import static dev.manuelantunes.axonposts.application.shared.AppendingDomainEventPublisher.appendingTo;

/**
 * O command <b>CreateTag</b>: a mensagem {@link CreateTag} e o que acontece quando ela chega. Mesma
 * forma do {@code CreatePostCommand} — {@code Optional<Tag>} para rejeitar id repetido e colocar o
 * stream na consistency boundary, decisão delegada ao domínio, e o resultado salvo dentro da transação.
 */
@Component
public class CreateTagCommand {

    /**
     * A mensagem: criar uma Tag. Como no {@code CreatePostCommand.CreatePost}, o id é gerado por quem
     * despacha, então o caller já sabe o id antes de a mensagem ser processada — é o que permite ao
     * handler que garante a tag padrão assinalá-la ao post na sequência.
     */
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

    /**
     * @return o id da Tag criada, que vira o payload do {@code CommandResultMessage}
     */
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
