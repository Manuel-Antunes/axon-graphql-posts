package dev.manuelantunes.axonposts.domain.tag.vo;

import dev.manuelantunes.axonposts.domain.tag.exception.InvalidTagException;
import jakarta.persistence.Embeddable;

@Embeddable
public record TagName(String value) {
    public static final int MAX_LENGTH = 50;

    public TagName {
        if (value == null || value.isBlank()) {
            throw new InvalidTagException("tag name must not be blank");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH) {
            throw new InvalidTagException("tag name exceeds " + MAX_LENGTH + " characters");
        }
    }

    public static TagName of(String value) {
        return new TagName(value);
    }

    public boolean sameAs(TagName other) {
        return other != null && value.equalsIgnoreCase(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
