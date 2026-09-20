package dev.manuelantunes.axonposts.domain.user.vo;

import dev.manuelantunes.axonposts.domain.user.exception.InvalidUserException;
import jakarta.persistence.Embeddable;

@Embeddable
public record DisplayName(String value) {
    public static final int MAX_LENGTH = 80;

    public DisplayName {
        if (value == null || value.isBlank()) {
            throw new InvalidUserException("name must not be blank");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH) {
            throw new InvalidUserException("name exceeds " + MAX_LENGTH + " characters");
        }
    }

    public static DisplayName of(String value) {
        return new DisplayName(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
