package dev.manuelantunes.axonposts.domain.tag.vo;

import dev.manuelantunes.axonposts.domain.tag.exception.InvalidTagException;
import jakarta.persistence.Embeddable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record TagId(@JsonValue String value) implements Serializable {
    public TagId {
        if (value == null || value.isBlank()) {
            throw new InvalidTagException("tagId must not be blank");
        }
        value = value.strip();
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TagId of(String value) {
        return new TagId(value);
    }

    public static TagId newId() {
        return new TagId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
