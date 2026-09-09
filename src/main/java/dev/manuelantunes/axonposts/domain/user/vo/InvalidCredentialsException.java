package dev.manuelantunes.axonposts.domain.user.vo;

/**
 * Login recusado. Sem detalhe de propósito: a mensagem é a mesma para e-mail inexistente, e-mail
 * malformado e senha errada.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("credenciais inválidas");
    }
}
