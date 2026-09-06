package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Command: atualizar título e/ou conteúdo de um Post. Campos {@code null} significam "manter o valor
 * atual" — quem sabe qual é o valor atual é a entidade, então o command só carrega a intenção.
 */
@Command(namespace = "posts", name = "UpdatePost", version = "1.0.0")
public record UpdatePostCommand(
        @TargetEntityId PostId postId,
        String title,
        String content
) {
}
