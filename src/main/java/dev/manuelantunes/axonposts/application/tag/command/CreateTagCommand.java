package dev.manuelantunes.axonposts.application.tag.command;

import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Command: criar uma Tag. Como no {@code CreatePostCommand}, o id é gerado por quem despacha, então o
 * caller já sabe o id antes de o command ser processado — é o que permite ao handler que garante a tag
 * padrão assinalá-la ao post na sequência.
 */
@Command(namespace = "tags", name = "CreateTag", version = "1.0.0")
public record CreateTagCommand(
        @TargetEntityId TagId tagId,
        String name
) {
}
