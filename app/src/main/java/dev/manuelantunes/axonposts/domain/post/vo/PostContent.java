package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import jakarta.persistence.Embeddable;

/**
 * Corpo do Post. Mesma ideia do {@link PostTitle}: valida e normaliza na construção, sem limite de
 * tamanho (a coluna é TEXT).
 */
@Embeddable
public record PostContent(String value) {

    public PostContent {
        if (value == null || value.isBlank()) {
            throw new InvalidPostException("content não pode ser vazio");
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
