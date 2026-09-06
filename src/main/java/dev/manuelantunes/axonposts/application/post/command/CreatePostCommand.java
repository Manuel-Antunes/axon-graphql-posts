package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Command: criar um Post. O id é gerado por quem despacha, assim o caller já sabe o id antes do command
 * ser processado (e o {@code createPost} do GraphQL consegue devolver o Post projetado).
 * <p>
 * {@code @TargetEntityId} (o {@code @TargetAggregateIdentifier} do Axon 5) é lido pelo
 * {@code @InjectEntity} do {@link CreatePostCommandHandler} para descobrir <i>qual</i> stream carregar.
 */
@Command(namespace = "posts", name = "CreatePost", version = "1.0.0")
public record CreatePostCommand(
        @TargetEntityId PostId postId,
        String title,
        String content,
        String author
) {
}
