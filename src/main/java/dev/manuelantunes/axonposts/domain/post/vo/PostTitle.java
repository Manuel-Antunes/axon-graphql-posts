package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;

/**
 * Título do Post. Value object: a invariante ("não vazio, no máximo {@value #MAX_LENGTH} caracteres")
 * e a normalização ({@code strip}) moram <b>aqui</b>, não espalhadas pela entidade ou pelos handlers.
 * <p>
 * Como o construtor canônico já valida, um {@code PostTitle} que existe é sempre válido — a entidade
 * {@code Post} não precisa checar nada ao guardá-lo.
 */
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

    /** Construtor nomeado: valida e normaliza o texto cru que veio de fora. */
    public static PostTitle of(String value) {
        return new PostTitle(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
