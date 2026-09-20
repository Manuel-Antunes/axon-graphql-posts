package dev.manuelantunes.axonposts.domain.user;

@FunctionalInterface
public interface PasswordVerifier {
    boolean matches(String rawPassword, String encodedPassword);
}
