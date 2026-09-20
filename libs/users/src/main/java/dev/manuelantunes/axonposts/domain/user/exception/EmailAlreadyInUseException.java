package dev.manuelantunes.axonposts.domain.user.exception;

public class EmailAlreadyInUseException extends RuntimeException {
    public EmailAlreadyInUseException() {
        super("este e-mail já está em uso");
    }
}
