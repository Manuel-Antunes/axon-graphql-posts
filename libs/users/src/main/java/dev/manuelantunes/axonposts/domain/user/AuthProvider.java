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

    /** O broker. Em dev e em teste, é por aqui que todo mundo entra. */
    KEYCLOAK,

    /**
     * O user pool do Cognito, que é quem emite na AWS desde que o Keycloak saiu de lá.
     *
     * <h2>Por que é um provedor PRÓPRIO, e não "mais um Keycloak"</h2>
     * Porque {@code uk_accounts_provider_subject} é {@code (provider, subject)}, e o {@code sub} que o
     * Cognito emite para uma pessoa não é o que o Keycloak emitia para a mesma pessoa. Sem esta
     * constante, as duas identidades seriam gravadas como {@link #KEYCLOAK} com subjects diferentes, e
     * {@code Authenticatable.link} recusaria a segunda com <i>"já tem conta em KEYCLOAK"</i> — foi
     * exatamente o que aconteceu na primeira execução contra o Cognito.
     * <p>
     * Com o provedor certo, o mesmo caso vira o que o desenho sempre quis: <b>account linking</b>. A
     * pessoa mantém um {@code users} e ganha uma segunda linha em {@code accounts}.
     *
     * <h2>De onde vem o alias</h2>
     * Da claim {@code identity_provider}, que o Cognito <b>não emite sozinho</b>: quem a põe é um
     * trigger <i>pre token generation</i> V1_0 (o que o tier Lite oferece), em
     * {@code infra/aws/cognito/identity-provider.mjs}. Sem ele o alias chega nulo e o default abaixo
     * responde {@link #KEYCLOAK} — que é o certo para dev e teste, e errado na AWS.
     */
    COGNITO,

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
            case "cognito" -> COGNITO;
            case "google" -> GOOGLE;
            case "github" -> GITHUB;
            default -> KEYCLOAK;
        };
    }
}
