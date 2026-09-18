package dev.manuelantunes.axonposts.domain.user.exception;

/** Invariante de {@code User}/{@code Author} violada: e-mail, nome ou id inválidos. */
public class InvalidUserException extends RuntimeException {

    public InvalidUserException(String message) {
        super(message);
    }
}
