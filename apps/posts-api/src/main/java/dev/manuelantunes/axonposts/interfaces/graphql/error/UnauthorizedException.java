package dev.manuelantunes.axonposts.interfaces.graphql.error;

import io.smallrye.graphql.api.ErrorCode;

@ErrorCode("UNAUTHORIZED")
public class UnauthorizedException extends ApplicationGraphQlException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
