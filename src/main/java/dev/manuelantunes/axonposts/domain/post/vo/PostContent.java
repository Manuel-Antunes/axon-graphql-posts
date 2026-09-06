package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;

/**
 * Corpo do Post. Value object com a mesma ideia do {@link PostTitle}: valida e normaliza na construção,
 * sem limite de tamanho (a coluna do read model é TEXT).
 */
public record PostContent(String value) {

    public PostContent {
        if (value == null || value.isBlank()) {
            throw new InvalidPostException("content não pode ser vazio");
        }
        value = value.strip();
    }

    /** Construtor nomeado: valida e normaliza o texto cru que veio de fora. */
    public static PostContent of(String value) {
        return new PostContent(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
