package dev.manuelantunes.axonposts.domain.user.vo;

import dev.manuelantunes.axonposts.domain.user.exception.InvalidUserException;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record AccountId(String value) implements Serializable {
    public AccountId {
        if (value == null || value.isBlank()) {
            throw new InvalidUserException("accountId must not be blank");
        }
        value = value.strip();
    }

    public static AccountId of(String value) {
        return new AccountId(value);
    }

    public static AccountId newId() {
        return new AccountId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
