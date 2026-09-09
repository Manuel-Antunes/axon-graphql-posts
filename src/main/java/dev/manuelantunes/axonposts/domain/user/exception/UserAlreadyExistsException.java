package dev.manuelantunes.axonposts.domain.user.exception;

import dev.manuelantunes.axonposts.domain.user.vo.UserId;

/** Tentativa de registrar um usuário com um id que já tem eventos no stream. */
public class UserAlreadyExistsException extends RuntimeException {

    public UserAlreadyExistsException(UserId userId) {
        super("Usuário já existe: " + userId);
    }
}
