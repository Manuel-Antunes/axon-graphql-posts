package dev.manuelantunes.axonposts.domain.shared;

/**
 * Apagar o que já está apagado.
 * <p>
 * Recusar em vez de ignorar é a mesma regra do {@code update} sem mudanças e do {@code assignTag}
 * repetido: se não há fato novo, não há o que registrar — e num agregado event-sourced, aceitar em
 * silêncio geraria um evento que não muda nada.
 */
public class AlreadyDeletedException extends RuntimeException {

    public AlreadyDeletedException(Object id) {
        super("já está apagado: " + id);
    }
}
