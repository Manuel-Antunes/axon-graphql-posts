package dev.manuelantunes.axonposts.domain.user.vo;

import dev.manuelantunes.axonposts.domain.user.exception.InvalidUserException;
import jakarta.persistence.Embeddable;

import java.util.regex.Pattern;

@Embeddable
public record Email(String value) {
    public static final int MAX_LENGTH = 254;

    private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public Email {
        if (value == null || value.isBlank()) {
            throw new InvalidUserException("email must not be blank");
        }
        value = value.strip().toLowerCase();
        if (value.length() > MAX_LENGTH) {
            throw new InvalidUserException("email exceeds " + MAX_LENGTH + " characters");
        }
        if (!SHAPE.matcher(value).matches()) {
            throw new InvalidUserException("invalid email: " + value);
        }
    }

    public static Email of(String value) {
        return new Email(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
