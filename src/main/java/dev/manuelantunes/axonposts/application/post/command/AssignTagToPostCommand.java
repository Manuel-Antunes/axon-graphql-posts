package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Command: assinalar uma tag já existente a um post.
 * <p>
 * Carrega {@code tagId} e {@code tagName} como texto, e não um {@code TagId}: o command atravessa a
 * fronteira entre dois agregados, e o que atravessa é dado. O nome vem junto porque o Post guarda uma
 * cópia dele ({@code TagRef}) — assim exibir um post não obriga a carregar o agregado Tag.
 */
@Command(namespace = "posts", name = "AssignTagToPost", version = "1.0.0")
public record AssignTagToPostCommand(
        @TargetEntityId PostId postId,
        String tagId,
        String tagName
) {
}
