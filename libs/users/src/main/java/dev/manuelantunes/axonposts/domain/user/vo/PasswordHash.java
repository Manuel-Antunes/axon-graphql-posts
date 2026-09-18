package dev.manuelantunes.axonposts.domain.user.vo;

import dev.manuelantunes.axonposts.domain.user.exception.InvalidUserException;
import jakarta.persistence.Embeddable;

/**
 * O hash da senha, <b>já calculado</b>.
 * <p>
 * O value object aceita o hash pronto e nunca a senha em claro: escolher o algoritmo é decisão de
 * infraestrutura ({@code PasswordEncoder}), e o domínio não deve ficar sabendo se é BCrypt, Argon2 ou o
 * que vier depois. O que o domínio garante é o que dá para garantir aqui — que não é vazio.
 * <p>
 * O {@code toString} é mascarado de propósito: um hash em log é material de ataque offline.
 */
@Embeddable
public record PasswordHash(String value) {

    public PasswordHash {
        if (value == null || value.isBlank()) {
            throw new InvalidUserException("hash de senha não pode ser vazio");
        }
    }

    public static PasswordHash of(String value) {
        return new PasswordHash(value);
    }

    @Override
    public String toString() {
        return "PasswordHash[protegido]";
    }
}
