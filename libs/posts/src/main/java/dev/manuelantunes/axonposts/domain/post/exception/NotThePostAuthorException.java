package dev.manuelantunes.axonposts.domain.post.exception;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;

public class NotThePostAuthorException extends RuntimeException {
    public NotThePostAuthorException(PostId postId, UserId actingAuthor) {
        super("o autor " + actingAuthor + " não pode alterar o post " + postId);
    }
}
