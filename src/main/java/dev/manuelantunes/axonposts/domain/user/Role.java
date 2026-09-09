package dev.manuelantunes.axonposts.domain.user;

/**
 * O papel de um usuário, <b>derivado do tipo</b> e não guardado numa coluna.
 * <p>
 * É a decisão central deste desenho: a hierarquia Java é a fonte da verdade, e a role existe só para
 * atravessar a fronteira do protocolo — ela vai como claim no JWT porque um filtro de segurança precisa
 * decidir sem ir ao banco. Guardar a role numa coluna <i>além</i> do tipo criaria duas verdades que
 * podem divergir; derivá-la de {@code instanceof Author} garante que a checagem barata do token e a
 * checagem cara do banco concordam.
 * <p>
 * O prefixo {@code ROLE_} é convenção do Spring Security: {@code hasRole('AUTHOR')} procura a authority
 * {@code ROLE_AUTHOR}.
 */
public enum Role {

    /** Todo usuário autenticado. */
    USER,

    /** Usuário que também é {@link Author} — pode escrever posts. */
    AUTHOR;

    public String authority() {
        return "ROLE_" + name();
    }
}
