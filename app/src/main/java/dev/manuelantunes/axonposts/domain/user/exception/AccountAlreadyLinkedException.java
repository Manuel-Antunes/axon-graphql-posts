package dev.manuelantunes.axonposts.domain.user.exception;

/**
 * Levantada pelo {@code DataIntegrityTranslator} a partir de uma violação de índice único.
 * <p>
 * Não existe checagem equivalente na aplicação de propósito: a unicidade é garantida pelo banco, que é o
 * único lugar sem janela de corrida entre verificar e escrever.
 */
public class AccountAlreadyLinkedException extends RuntimeException {

    public AccountAlreadyLinkedException() {
        super("esta conta de provedor já está ligada a outro usuário");
    }
}
