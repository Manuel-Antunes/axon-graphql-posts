package dev.manuelantunes.axonposts.interfaces.graphql.error;

public abstract class ApplicationGraphQlException extends RuntimeException {
    protected ApplicationGraphQlException(String message) {
        super(message);
    }
}
