package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.UUID;

/**
 * Identidade do Post. Value object imutável, validado e <b>mapeado</b>.
 * <p>
 * {@code @Embeddable} num {@code record}: o Hibernate instancia pelo construtor canônico, então a
 * invariante roda também quando a linha volta do banco. A chave primária pede
 * {@link Serializable} — daí a interface.
 * <p>
 * Três papéis num tipo só: {@code @EmbeddedId} da entidade JPA, {@code idType} da entidade do Axon e
 * {@code @TargetEntityId} dos commands. O {@link #toString()} devolve o valor cru <b>de propósito</b> —
 * é ele que vira o valor da tag ({@code postId=<uuid>}) no event store.
 */
@Embeddable
public record PostId(String value) implements Serializable {

    public PostId {
        if (value == null || value.isBlank()) {
            throw new InvalidPostException("postId não pode ser vazio");
        }
        value = value.strip();
    }

    /** Id que já existe — veio de fora (GraphQL, outro contexto). */
    public static PostId of(String value) {
        return new PostId(value);
    }

    /** Id novo, gerado antes do command ser despachado, para o caller já saber o id. */
    public static PostId newId() {
        return new PostId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
