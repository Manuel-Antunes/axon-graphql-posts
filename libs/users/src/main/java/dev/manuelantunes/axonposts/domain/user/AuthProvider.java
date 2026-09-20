package dev.manuelantunes.axonposts.domain.user;

public enum AuthProvider {
    CREDENTIAL,

    KEYCLOAK,

    COGNITO,

    GOOGLE,
    GITHUB;

    public boolean isFederated() {
        return this != CREDENTIAL;
    }

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
