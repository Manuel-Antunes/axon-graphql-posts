package dev.manuelantunes.axonposts.application.auth;

import dev.manuelantunes.axonposts.domain.user.AuthProvider;

public record Identity(
        AuthProvider provider,
        String subject,
        String email,
        String name,
        boolean author
) {
}
