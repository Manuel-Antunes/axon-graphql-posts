package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import jakarta.persistence.Embeddable;

@Embeddable
public record PostVersion(long value) {
    public PostVersion {
        if (value < 1) {
            throw new InvalidPostException("version starts at 1, received " + value);
        }
    }

    public static PostVersion initial() {
        return new PostVersion(1);
    }

    public PostVersion next() {
        return new PostVersion(value + 1);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
