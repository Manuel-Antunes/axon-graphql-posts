package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import jakarta.persistence.Embeddable;

/**
 * Versão do Post: quantos eventos já foram aplicados a ele (1 = só criado).
 * <p>
 * Não é {@code @Version} do JPA de propósito: aquele é um contador de lock otimista gerenciado pelo
 * Hibernate, este é o contador da reconstituição por eventos. Quem manda nele é o stream, não o ORM.
 * <p>
 * Só se anda para frente: {@link #initial()} e {@link #next()} são as duas únicas formas de obter uma.
 */
@Embeddable
public record PostVersion(long value) {

    public PostVersion {
        if (value < 1) {
            throw new InvalidPostException("version começa em 1, recebido " + value);
        }
    }

    public static PostVersion initial() {
        return new PostVersion(1);
    }

    public PostVersion next() {
        return new PostVersion(value + 1);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
