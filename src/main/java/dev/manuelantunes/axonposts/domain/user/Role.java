package dev.manuelantunes.axonposts.domain.user;

import java.util.Locale;

/**
 * O papel de um usuário, <b>derivado do tipo</b> e não guardado numa coluna.
 * <p>
 * É a decisão central deste desenho: a hierarquia Java é a fonte da verdade, e a role existe só para
 * atravessar a fronteira do protocolo — ela vai como claim no JWT porque a camada de segurança precisa
 * decidir sem ir ao banco. Guardar a role numa coluna <i>além</i> do tipo criaria duas verdades que
 * podem divergir; derivá-la de {@code instanceof Author} garante que a checagem barata do token e a
 * checagem cara do banco concordam.
 *
 * <h2>Sem prefixo, ao contrário da versão Spring</h2>
 * No Spring Security a convenção obriga a authority a se chamar {@code ROLE_AUTHOR} para que
 * {@code hasRole('AUTHOR')} case, e um conversor à mão tinha de acrescentar o prefixo às roles que o
 * Keycloak escreve em {@code realm_access.roles}.
 * <p>
 * No Quarkus não há prefixo nem conversor: o {@code quarkus-oidc} já lê {@code realm_access.roles} de um
 * token do Keycloak e põe cada valor, <b>como está</b>, no {@code SecurityIdentity}. Então
 * {@code @RolesAllowed("author")} casa com o que o realm emite, e {@link #claim()} é exatamente esse
 * valor — o nome do enum em minúsculas.
 */
public enum Role {

    /** Todo usuário autenticado. */
    USER,

    /** Usuário que também é {@link Author} — pode escrever posts. */
    AUTHOR;

    /** Nome desta role <b>no token</b>, como o Keycloak a escreve e como {@code @RolesAllowed} a espera. */
    public String claim() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Constante para {@code @RolesAllowed}: anotação exige literal em tempo de compilação, então não dá
     * para chamar {@link #claim()} lá. Manter os dois lado a lado é o que torna a divergência visível.
     */
    public static final String AUTHOR_CLAIM = "author";

    /** A contraparte de {@link #AUTHOR_CLAIM}. */
    public static final String USER_CLAIM = "user";
}
