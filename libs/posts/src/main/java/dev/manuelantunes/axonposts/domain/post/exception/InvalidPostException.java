package dev.manuelantunes.axonposts.domain.post.exception;

public class InvalidPostException extends RuntimeException {
    public InvalidPostException(String message) {
        super(message);
    }
}
