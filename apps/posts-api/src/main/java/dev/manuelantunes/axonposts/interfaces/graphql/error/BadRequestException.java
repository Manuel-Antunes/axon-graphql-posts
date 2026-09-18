package dev.manuelantunes.axonposts.interfaces.graphql.error;

import io.smallrye.graphql.api.ErrorCode;

/**
 * O que o cliente mandou não serve: input malformado ou invariante de domínio violada.
 * <p>
 * As duas alturas de validação desembocam aqui — a Bean Validation dos {@code *Input}, na borda, e os
 * value objects do domínio, mais fundo. O cliente não precisa saber qual delas o pegou.
 */
@ErrorCode("BAD_REQUEST")
public class BadRequestException extends ApplicationGraphQlException {

    public BadRequestException(String message) {
        super(message);
    }
}
