package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;

/**
 * Autor do Post. Value object: quem escreveu é imutável depois da criação — não existe evento que troque
 * o autor, e é por isso que {@code Post.update(...)} nem recebe esse campo.
 */
public record Author(String value) {

    public Author {
        if (value == null || value.isBlank()) {
            throw new InvalidPostException("author não pode ser vazio");
        }
        value = value.strip();
    }

    /** Construtor nomeado: valida e normaliza o nome cru que veio de fora. */
    public static Author of(String value) {
        return new Author(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
