package dev.manuelantunes.axonposts.domain.user.exception;

import dev.manuelantunes.axonposts.domain.user.vo.UserId;

/**
 * O usuário autenticado existe, mas não é um {@code Author} — então não pode escrever posts.
 * <p>
 * Na prática o {@code @PreAuthorize("hasRole('AUTHOR')")} do controller barra antes, pela role que veio
 * no token. Esta exceção cobre o caso em que as duas verdades divergem: token diz AUTHOR, a linha em
 * {@code authors} não existe (papel revogado com token ainda válido). O tipo Java é a verdade final.
 */
public class NotAnAuthorException extends RuntimeException {

    public NotAnAuthorException(UserId userId) {
        super("Usuário não é um autor: " + userId);
    }
}
