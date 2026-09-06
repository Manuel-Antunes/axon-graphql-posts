package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;

import java.util.UUID;

/**
 * Identidade do Post. Value object imutável e validado.
 * <p>
 * É também o {@code idType} da entidade no Axon: aparece como {@code @TargetEntityId} nos commands e
 * como {@code @EventTag} nos eventos. O {@link #toString()} devolve o valor cru <b>de propósito</b> —
 * é ele que vira o valor da tag ({@code postId=<uuid>}) no event store.
 * <p>
 * Construtores nomeados: {@link #of(String)} para um id que já existe (veio do cliente) e
 * {@link #newId()} para um id novo. A leitura no call site diz qual dos dois casos é.
 */
public record PostId(String value) {

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
