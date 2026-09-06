package dev.manuelantunes.axonposts.application.tag.command;

import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.exception.TagAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

import static dev.manuelantunes.axonposts.application.shared.AppendingDomainEventPublisher.appendingTo;

/**
 * Handler de <b>um</b> command: {@link CreateTagCommand}. Mesma forma do handler de criação de Post —
 * {@code Optional<Tag>} para rejeitar id repetido e colocar o stream na consistency boundary, decisão
 * delegada ao domínio, e o resultado salvo dentro da transação do command.
 */
@Component
public class CreateTagCommandHandler {

    private final Clock clock;
    private final TagRepository tags;

    public CreateTagCommandHandler(Clock clock, TagRepository tags) {
        this.clock = clock;
        this.tags = tags;
    }

    /**
     * @return o id da Tag criada, que vira o payload do {@code CommandResultMessage}
     */
    @CommandHandler
    public TagId handle(CreateTagCommand command,
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
