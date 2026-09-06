package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;

/**
 * Versão do Post: quantos eventos já foram aplicados a ele (1 = só criado).
 * <p>
 * É estado de domínio, não enfeite do read model: como a entidade é reconstituída evento a evento, a
 * versão é o contador dessa reconstituição — e é ela que o comando grava no read model para o cliente
 * saber qual "geração" do Post está vendo.
 * <p>
 * Só se anda para frente: {@link #initial()} e {@link #next()} são as duas únicas formas de obter uma.
 */
public record PostVersion(long value) {

    public PostVersion {
        if (value < 1) {
            throw new InvalidPostException("version começa em 1, recebido " + value);
        }
    }

    /** A versão de um Post que acabou de nascer do seu primeiro evento. */
    public static PostVersion initial() {
        return new PostVersion(1);
    }

    /** A versão depois de aplicar mais um evento. */
    public PostVersion next() {
        return new PostVersion(value + 1);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
