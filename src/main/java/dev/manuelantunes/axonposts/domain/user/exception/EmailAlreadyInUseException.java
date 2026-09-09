package dev.manuelantunes.axonposts.domain.user.exception;

/**
 * Levantada pelo {@code DataIntegrityTranslator} a partir de uma violação de índice único.
 * <p>
 * Não existe checagem equivalente na aplicação de propósito: a unicidade é garantida pelo banco, que é o
 * único lugar sem janela de corrida entre verificar e escrever.
 */
public class EmailAlreadyInUseException extends RuntimeException {

    public EmailAlreadyInUseException() {
        super("este e-mail já está em uso");
    }
}
