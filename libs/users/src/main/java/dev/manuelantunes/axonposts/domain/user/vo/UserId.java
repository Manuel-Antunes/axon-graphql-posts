package dev.manuelantunes.axonposts.domain.user.vo;

import dev.manuelantunes.axonposts.domain.user.exception.InvalidUserException;
import jakarta.persistence.Embeddable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record UserId(@JsonValue String value) implements Serializable {
    public UserId {
        if (value == null || value.isBlank()) {
            throw new InvalidUserException("userId must not be blank");
        }
        value = value.strip();
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static UserId of(String value) {
        return new UserId(value);
    }

    public static UserId newId() {
        return new UserId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
