package dev.manuelantunes.axonposts.domain.shared;

public class NotDeletedException extends RuntimeException {
    public NotDeletedException(Object id) {
        super("não está apagado: " + id);
    }
}
