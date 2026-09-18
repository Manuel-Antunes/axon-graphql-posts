package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import jakarta.persistence.Embeddable;

/**
 * Título do Post. A invariante ("não vazio, no máximo {@value #MAX_LENGTH} caracteres") e a
 * normalização ({@code strip}) moram aqui, não espalhadas pela entidade ou pelos handlers.
 * <p>
 * Como {@code @Embeddable}, ele é o próprio mapeamento da coluna: não existe um "PostTitle de domínio"
 * e um "String de infraestrutura" para manter em sincronia. A entidade declara o
 * {@code @AttributeOverride} que dá nome à coluna.
 */
@Embeddable
public record PostTitle(String value) {

    public static final int MAX_LENGTH = 200;

    public PostTitle {
        if (value == null || value.isBlank()) {
            throw new InvalidPostException("title não pode ser vazio");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH) {
            throw new InvalidPostException("title excede " + MAX_LENGTH + " caracteres");
        }
    }

    public static PostTitle of(String value) {
        return new PostTitle(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
