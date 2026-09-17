package dev.manuelantunes.axonposts.interfaces.graphql.error;

import io.smallrye.graphql.api.ErrorCode;

/** Token ausente, expirado ou de outro emissor: tudo "identifique-se". */
@ErrorCode("UNAUTHORIZED")
public class UnauthorizedException extends ApplicationGraphQlException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
