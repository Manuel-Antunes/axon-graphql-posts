package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import jakarta.persistence.Embeddable;

/**
 * Autor do Post. Quem escreveu é imutável depois da criação — não existe evento que troque o autor, e é
 * por isso que {@code Post.update(...)} nem recebe esse campo.
 */
@Embeddable
public record Author(String value) {

    public Author {
        if (value == null || value.isBlank()) {
            throw new InvalidPostException("author não pode ser vazio");
        }
        value = value.strip();
    }

    public static Author of(String value) {
        return new Author(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
