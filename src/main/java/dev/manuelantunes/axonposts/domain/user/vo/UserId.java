package dev.manuelantunes.axonposts.domain.user.vo;

import dev.manuelantunes.axonposts.domain.user.exception.InvalidUserException;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.UUID;

/**
 * Identidade de um usuário — e, por tabela-por-tipo, também a de um {@code Author}: a subclasse não tem
 * id próprio, ela compartilha o do {@code User}. É por isso que não existe um {@code AuthorId}.
 * <p>
 * Mesmo desenho do {@code PostId} e do {@code TagId}: {@code @EmbeddedId} e {@code @TargetEntityId}.
 */
@Embeddable
public record UserId(String value) implements Serializable {

    public UserId {
        if (value == null || value.isBlank()) {
            throw new InvalidUserException("userId não pode ser vazio");
        }
        value = value.strip();
    }

    public static UserId of(String value) {
        return new UserId(value);
    }

    public static UserId newId() {
        return new UserId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
