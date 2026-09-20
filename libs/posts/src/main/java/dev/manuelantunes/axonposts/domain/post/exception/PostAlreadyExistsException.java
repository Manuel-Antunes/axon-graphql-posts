package dev.manuelantunes.axonposts.domain.post.exception;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;

public class PostAlreadyExistsException extends RuntimeException {
    public PostAlreadyExistsException(PostId postId) {
        super("Post já existe: " + postId);
    }
}
