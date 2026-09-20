package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import jakarta.persistence.Embeddable;

@Embeddable
public record PostTitle(String value) {
    public static final int MAX_LENGTH = 200;

    public PostTitle {
        if (value == null || value.isBlank()) {
            throw new InvalidPostException("title must not be blank");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH) {
            throw new InvalidPostException("title exceeds " + MAX_LENGTH + " characters");
        }
    }

    public static PostTitle of(String value) {
        return new PostTitle(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
