package dev.manuelantunes.axonposts.domain.user.vo;

import dev.manuelantunes.axonposts.domain.user.exception.InvalidUserException;
import jakarta.persistence.Embeddable;

import java.util.regex.Pattern;

/**
 * E-mail do usuário: é por ele que se faz login, então é ele que precisa ser único e normalizado.
 * <p>
 * Guardado sempre em minúsculas — não porque "fica bonito", mas porque a unicidade depende disso:
 * {@code Manuel@x.com} e {@code manuel@x.com} são a mesma conta, e um índice único sobre a coluna só
 * enxerga isso se o valor já chegar normalizado.
 */
@Embeddable
public record Email(String value) {

    public static final int MAX_LENGTH = 254;

    /** Deliberadamente frouxo: barra o obviamente errado sem tentar implementar a RFC 5322. */
    private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public Email {
        if (value == null || value.isBlank()) {
            throw new InvalidUserException("email não pode ser vazio");
        }
        value = value.strip().toLowerCase();
        if (value.length() > MAX_LENGTH) {
            throw new InvalidUserException("email excede " + MAX_LENGTH + " caracteres");
        }
        if (!SHAPE.matcher(value).matches()) {
            throw new InvalidUserException("email inválido: " + value);
        }
    }

    public static Email of(String value) {
        return new Email(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
