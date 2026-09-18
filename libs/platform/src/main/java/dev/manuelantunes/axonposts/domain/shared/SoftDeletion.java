package dev.manuelantunes.axonposts.domain.shared;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.Instant;
import java.util.Objects;

/**
 * O estado de exclusão lógica: {@code null} = vivo, um instante = apagado naquele momento.
 *
 * <h2>Por que uma classe, e não um campo {@code Instant} solto na entidade</h2>
 * Porque é o que permite ao {@link SoftDeletable} ter {@code delete}/{@code restore} como
 * <b>default methods</b> sem expor um setter público.
 * <p>
 * Um mixin com métodos default que mudam estado precisa de algum jeito de mudar esse estado, e toda
 * declaração numa interface Java é pública. Se o contrato fosse {@code void deletedAt(Instant)}, qualquer
 * código do sistema poderia marcar e desmarcar a exclusão por fora das regras — exatamente o que os
 * defaults existem para centralizar. Com este holder, o mixin pede <b>uma</b> propriedade de leitura
 * ({@code softDeletion()}) e a mutação fica trancada aqui dentro.
 * <p>
 * Mutável de propósito, ao contrário dos outros value objects do projeto: ele é o pedaço de estado que o
 * JPA gerencia e que os defaults do mixin alteram. É o mesmo motivo por que as entidades têm campos
 * não-finais.
 *
 * <h2>Timestamp em vez de boolean</h2>
 * Um {@code boolean deleted} responde "está apagado?"; o instante responde também "desde quando", que é
 * a pergunta que aparece assim que alguém precisa auditar ou expirar apagados. Custa a mesma coluna.
 */
@Embeddable
public class SoftDeletion {

    /** Nome da coluna. Usado literalmente nos {@code @SQLRestriction} / {@code @SQLDelete}. */
    public static final String COLUMN = "deleted_at";

    @Column(name = COLUMN)
    private Instant deletedAt;

    /** Exigido pelo JPA, e o estado inicial de tudo: vivo. */
    public SoftDeletion() {
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** {@code null} quando vivo. */
    public Instant at() {
        return deletedAt;
    }

    /** Pacote-visível não dá (o mixin está no mesmo pacote, mas as entidades não). Ver o javadoc acima. */
    void delete(Instant now) {
        this.deletedAt = Objects.requireNonNull(now, "now");
    }

    void restore() {
        this.deletedAt = null;
    }

    @Override
    public String toString() {
        return isDeleted() ? "apagado em " + deletedAt : "vivo";
    }
}
