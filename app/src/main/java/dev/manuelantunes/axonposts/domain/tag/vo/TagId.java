package dev.manuelantunes.axonposts.domain.tag.vo;

import dev.manuelantunes.axonposts.domain.tag.exception.InvalidTagException;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.UUID;

/**
 * Identidade da Tag. Mesmo desenho do {@code PostId}: {@code @EmbeddedId} da entidade JPA, tipo de id do
 * {@code EventSourcedEntityModule} e {@code @TargetEntityId} dos commands.
 */
@Embeddable
public record TagId(String value) implements Serializable {

    public TagId {
        if (value == null || value.isBlank()) {
            throw new InvalidTagException("tagId não pode ser vazio");
        }
        value = value.strip();
    }

    public static TagId of(String value) {
        return new TagId(value);
    }

    public static TagId newId() {
        return new TagId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
