package dev.manuelantunes.axonposts.domain.user;

/**
 * De onde vem uma credencial.
 * <p>
 * Um {@link User} pode ter mais de uma {@link Account}, uma por provedor — é o <i>account linking</i>:
 * a mesma pessoa entra pelo Keycloak hoje e pelo GitHub amanhã, e continua sendo o mesmo usuário local
 * com os mesmos posts.
 * <p>
 * O enum é fechado de propósito. Provedor é decisão de arquitetura, não dado de entrada: um valor novo
 * aqui obriga a decidir o que fazer com ele em tempo de compilação.
 */
public enum AuthProvider {

    /**
     * Senha guardada localmente. Sobrevive como <b>modelo</b> depois da migração para o Keycloak: é o
     * caso "esta conta tem senha" do account linking, e o que distingue uma conta que pode entrar por
     * formulário de uma que só existe via federação.
     */
    CREDENTIAL,

    /** O broker. Depois da migração, é por aqui que todo mundo entra. */
    KEYCLOAK,

    /** Provedores federados que o Keycloak intermedia; chegam pela claim {@code identity_provider}. */
    GOOGLE,
    GITHUB;

    /** {@code true} para todo provedor externo — ou seja, todos menos {@link #CREDENTIAL}. */
    public boolean isFederated() {
        return this != CREDENTIAL;
    }

    /** O nome como o Keycloak escreve um alias de identity provider. */
    public static AuthProvider fromAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return KEYCLOAK;
        }
        return switch (alias.strip().toLowerCase()) {
            case "google" -> GOOGLE;
            case "github" -> GITHUB;
            default -> KEYCLOAK;
        };
    }
}
