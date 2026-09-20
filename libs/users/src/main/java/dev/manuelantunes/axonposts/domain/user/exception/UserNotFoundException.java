package dev.manuelantunes.axonposts.domain.user.exception;

import dev.manuelantunes.axonposts.domain.user.vo.UserId;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(UserId userId) {
        super("Usuário não encontrado: " + userId);
    }
}
