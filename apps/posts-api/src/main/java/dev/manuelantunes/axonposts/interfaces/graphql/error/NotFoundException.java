package dev.manuelantunes.axonposts.interfaces.graphql.error;

import io.smallrye.graphql.api.ErrorCode;

@ErrorCode("NOT_FOUND")
public class NotFoundException extends ApplicationGraphQlException {
    public NotFoundException(String message) {
        super(message);
    }
}
