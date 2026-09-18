package dev.manuelantunes.axonposts.interfaces.graphql.error;

import io.smallrye.graphql.api.ErrorCode;

/** O recurso nomeado não existe. */
@ErrorCode("NOT_FOUND")
public class NotFoundException extends ApplicationGraphQlException {

    public NotFoundException(String message) {
        super(message);
    }
}
