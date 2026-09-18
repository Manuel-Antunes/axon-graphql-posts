package dev.manuelantunes.axonposts.domain.post.exception;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;

/**
 * Um autor tentou mexer no post de outro.
 * <p>
 * A mensagem <b>não</b> diz quem é o dono: para quem está sondando, "não é seu" e "não existe" precisam
 * ser indistinguíveis, senão a recusa vira um oráculo de quem escreveu o quê.
 */
public class NotThePostAuthorException extends RuntimeException {

    public NotThePostAuthorException(PostId postId, UserId actingAuthor) {
        super("o autor " + actingAuthor + " não pode alterar o post " + postId);
    }
}
