package dev.manuelantunes.axonposts.interfaces.graphql.error;

import io.smallrye.graphql.api.ErrorCode;

@ErrorCode("FORBIDDEN")
public class ForbiddenException extends ApplicationGraphQlException {
    public ForbiddenException(String message) {
        super(message);
    }
}
