package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import jakarta.persistence.Embeddable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record PostId(@JsonValue String value) implements Serializable {
    public PostId {
        if (value == null || value.isBlank()) {
            throw new InvalidPostException("postId must not be blank");
        }
        value = value.strip();
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PostId of(String value) {
        return new PostId(value);
    }

    public static PostId newId() {
        return new PostId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
