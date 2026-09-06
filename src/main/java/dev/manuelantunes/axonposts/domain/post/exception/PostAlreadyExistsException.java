package dev.manuelantunes.axonposts.domain.post.exception;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;

/**
 * Tentativa de criar um Post com um id que já tem eventos no stream.
 * <p>
 * Quem detecta é o {@code CreatePostCommandHandler} (só ele enxerga o estado carregado pelo Axon), mas
 * o <b>vocabulário</b> é do domínio — por isso a exceção mora aqui.
 */
public class PostAlreadyExistsException extends RuntimeException {

    public PostAlreadyExistsException(PostId postId) {
        super("Post já existe: " + postId);
    }
}
