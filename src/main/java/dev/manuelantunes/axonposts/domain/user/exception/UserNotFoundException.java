package dev.manuelantunes.axonposts.domain.user.exception;

import dev.manuelantunes.axonposts.domain.user.vo.UserId;

/** O usuário do token não existe mais no banco. */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UserId userId) {
        super("Usuário não encontrado: " + userId);
    }
}
