package dev.manuelantunes.axonposts.domain.user.exception;

import dev.manuelantunes.axonposts.domain.user.vo.UserId;

public class UserAlreadyExistsException extends RuntimeException {
    public UserAlreadyExistsException(UserId userId) {
        super("Usuário já existe: " + userId);
    }
}
