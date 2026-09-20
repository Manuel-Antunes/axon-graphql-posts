package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.user.vo.PasswordHash;

import java.util.Optional;

public interface Authenticatable {
    AuthProvider provider();

    String subject();

    Optional<PasswordHash> passwordHash();

    default boolean hasPassword() {
        return passwordHash().isPresent();
    }

    default boolean isFederated() {
        return provider().isFederated();
    }

    default boolean authenticates(String rawPassword, PasswordVerifier verifier) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return false;
        }
        return passwordHash()
                .map(hash -> verifier.matches(rawPassword, hash.value()))
                .orElse(false);
    }

    default boolean identifies(AuthProvider provider, String subject) {
        return provider() == provider && subject().equals(subject);
    }
}
