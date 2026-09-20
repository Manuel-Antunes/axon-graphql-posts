package dev.manuelantunes.axonposts.interfaces.graphql.error;

import io.smallrye.graphql.api.ErrorCode;

@ErrorCode("BAD_REQUEST")
public class BadRequestException extends ApplicationGraphQlException {
    public BadRequestException(String message) {
        super(message);
    }
}
