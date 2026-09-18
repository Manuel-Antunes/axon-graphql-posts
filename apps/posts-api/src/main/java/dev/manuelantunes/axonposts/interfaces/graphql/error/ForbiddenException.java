package dev.manuelantunes.axonposts.interfaces.graphql.error;

import io.smallrye.graphql.api.ErrorCode;

/**
 * Autenticado, mas sem permissão.
 * <p>
 * A mensagem não diz <b>o que</b> faltou, só que faltou: distinguir "você não é autor" de "este post é de
 * outro" transformaria a recusa num oráculo de quem escreveu o quê.
 */
@ErrorCode("FORBIDDEN")
public class ForbiddenException extends ApplicationGraphQlException {

    public ForbiddenException(String message) {
        super(message);
    }
}
