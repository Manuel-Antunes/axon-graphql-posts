package dev.manuelantunes.axonposts.domain.user.exception;

public class AccountAlreadyLinkedException extends RuntimeException {
    public AccountAlreadyLinkedException() {
        super("esta conta de provedor já está ligada a outro usuário");
    }
}
