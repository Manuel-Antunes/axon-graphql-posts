package dev.manuelantunes.axonposts.domain.tag.vo;

import dev.manuelantunes.axonposts.domain.tag.exception.InvalidTagException;
import jakarta.persistence.Embeddable;

/**
 * Nome da Tag. É por ele que uma tag é reconhecida, então a normalização importa: {@code strip} e
 * comparação sem diferenciar maiúsculas evitam "Untagged" e "untagged" virarem duas tags.
 */
@Embeddable
public record TagName(String value) {

    public static final int MAX_LENGTH = 50;

    public TagName {
        if (value == null || value.isBlank()) {
            throw new InvalidTagException("nome da tag não pode ser vazio");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH) {
            throw new InvalidTagException("nome da tag excede " + MAX_LENGTH + " caracteres");
        }
    }

    public static TagName of(String value) {
        return new TagName(value);
    }

    /** Duas tags têm o mesmo nome se diferem só em caixa ou espaços nas pontas. */
    public boolean sameAs(TagName other) {
        return other != null && value.equalsIgnoreCase(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
