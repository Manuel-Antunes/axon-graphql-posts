package dev.manuelantunes.axonposts.domain.tag.exception;

/** Violação de invariante do domínio de Tag (nome vazio ou longo demais). */
public class InvalidTagException extends RuntimeException {

    public InvalidTagException(String message) {
        super(message);
    }
}
