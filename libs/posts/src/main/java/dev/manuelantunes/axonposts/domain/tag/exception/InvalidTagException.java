package dev.manuelantunes.axonposts.domain.tag.exception;

public class InvalidTagException extends RuntimeException {
    public InvalidTagException(String message) {
        super(message);
    }
}
