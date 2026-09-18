package dev.manuelantunes.axonposts.domain.post.exception;

/**
 * Violação de invariante do domínio de Post (título vazio, update sem mudanças etc.).
 * <p>
 * É lançada pelos value objects ({@code PostTitle}, {@code PostContent}, {@code Author}) e pelas
 * decisões da entidade {@code Post} — nunca pela camada de aplicação.
 */
public class InvalidPostException extends RuntimeException {

    public InvalidPostException(String message) {
        super(message);
    }
}
