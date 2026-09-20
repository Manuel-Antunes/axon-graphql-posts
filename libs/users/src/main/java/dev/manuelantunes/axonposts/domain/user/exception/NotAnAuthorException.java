package dev.manuelantunes.axonposts.domain.user.exception;

import dev.manuelantunes.axonposts.domain.user.vo.UserId;

public class NotAnAuthorException extends RuntimeException {
    public NotAnAuthorException(UserId userId) {
        super("Usuário não é um autor: " + userId);
    }

    public NotAnAuthorException() {
        super("o autor informado não existe ou não pode escrever");
    }
}
