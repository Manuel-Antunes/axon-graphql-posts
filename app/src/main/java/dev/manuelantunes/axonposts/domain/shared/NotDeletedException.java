package dev.manuelantunes.axonposts.domain.shared;

/** Restaurar o que não está apagado. Pelo mesmo motivo do {@link AlreadyDeletedException}. */
public class NotDeletedException extends RuntimeException {

    public NotDeletedException(Object id) {
        super("não está apagado: " + id);
    }
}
