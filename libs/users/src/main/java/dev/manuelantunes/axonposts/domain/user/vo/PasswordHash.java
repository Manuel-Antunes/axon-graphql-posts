package dev.manuelantunes.axonposts.domain.user.vo;

import dev.manuelantunes.axonposts.domain.user.exception.InvalidUserException;
import jakarta.persistence.Embeddable;

@Embeddable
public record PasswordHash(String value) {
    public PasswordHash {
        if (value == null || value.isBlank()) {
            throw new InvalidUserException("password hash must not be blank");
        }
    }

    public static PasswordHash of(String value) {
        return new PasswordHash(value);
    }

    @Override
    public String toString() {
        return "PasswordHash[protegido]";
    }
}
