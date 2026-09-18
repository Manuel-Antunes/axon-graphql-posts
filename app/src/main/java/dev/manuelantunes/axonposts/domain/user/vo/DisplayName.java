package dev.manuelantunes.axonposts.domain.user.vo;

import dev.manuelantunes.axonposts.domain.user.exception.InvalidUserException;
import jakarta.persistence.Embeddable;

/**
 * O nome como o usuário aparece — o que antes era o {@code Author} value object do pacote de Post, um
 * texto solto copiado para dentro de cada post.
 */
@Embeddable
public record DisplayName(String value) {

    public static final int MAX_LENGTH = 80;

    public DisplayName {
        if (value == null || value.isBlank()) {
            throw new InvalidUserException("nome não pode ser vazio");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH) {
            throw new InvalidUserException("nome excede " + MAX_LENGTH + " caracteres");
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
