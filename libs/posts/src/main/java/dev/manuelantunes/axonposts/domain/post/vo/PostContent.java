package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import jakarta.persistence.Embeddable;

@Embeddable
public record PostContent(String value) {
    public PostContent {
        if (value == null || value.isBlank()) {
            throw new InvalidPostException("content must not be blank");
        }
        value = value.strip();
    }

    public static PostContent of(String value) {
        return new PostContent(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
