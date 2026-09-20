package dev.manuelantunes.axonposts.domain.shared;

public class AlreadyDeletedException extends RuntimeException {
    public AlreadyDeletedException(Object id) {
        super("já está apagado: " + id);
    }
}
