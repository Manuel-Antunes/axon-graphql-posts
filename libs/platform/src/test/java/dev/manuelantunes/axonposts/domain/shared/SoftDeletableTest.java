package dev.manuelantunes.axonposts.domain.shared;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * As regras de {@link SoftDeletable}, como {@link EmbeddableSoftDeletable} as entrega, testadas
 * <b>sozinhas</b>: sem Post, sem User, sem JPA.
 * <p>
 * É o que justifica o mixin ser uma interface com defaults: o comportamento é testável uma vez, sobre a
 * classe de teste abaixo, e as duas entidades que o usam herdam o teste junto com o código.
 */
class SoftDeletableTest {

    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);

    /**
     * O mínimo que o mixin exige: o holder e uma identidade.
     */
    private static final class Thing implements SoftDeletable, EmbeddableSoftDeletable {

        private final SoftDeletion softDeletion = new SoftDeletion();

        @Override
        public SoftDeletion softDeletion() {
            return softDeletion;
        }

        @Override
        public Object identity() {
            return "coisa-1";
        }
    }

    @Test
    void startsAlive() {
        Thing thing = new Thing();

        assertThat(thing.isDeleted()).isFalse();
        assertThat(thing.deletedAt()).isNull();
    }

    @Test
    void deleteRecordsTheInstant() {
        Thing thing = new Thing();

        thing.delete(T0);

        assertThat(thing.isDeleted()).isTrue();
        assertThat(thing.deletedAt()).isEqualTo(T0);
    }

    @Test
    void restoreBringsItBackToLife() {
        Thing thing = new Thing();
        thing.delete(T0);

        thing.restore();

        assertThat(thing.isDeleted()).isFalse();
        assertThat(thing.deletedAt()).isNull();
    }

    @Test
    void deletingTwiceIsRejected() {
        Thing thing = new Thing();
        thing.delete(T0);

        assertThatThrownBy(() -> thing.delete(T1))
                .isInstanceOf(AlreadyDeletedException.class)
                .hasMessageContaining("coisa-1");

        // e o instante original não foi sobrescrito
        assertThat(thing.deletedAt()).isEqualTo(T0);
    }

    @Test
    void restoringSomethingAliveIsRejected() {
        assertThatThrownBy(() -> new Thing().restore())
                .isInstanceOf(NotDeletedException.class);
    }

    @Test
    void applyDeletionIsIdempotentUnlikeDelete() {
        Thing thing = new Thing();

        // é o que o @EventSourcingHandler faz: o mesmo evento chega duas vezes e não pode estourar
        thing.applyDeletion(T0);
        thing.applyDeletion(T0);

        assertThat(thing.isDeleted()).isTrue();
        assertThat(thing.deletedAt()).isEqualTo(T0);
    }

    @Test
    void applyRestorationIsIdempotentToo() {
        Thing thing = new Thing();
        thing.applyDeletion(T0);

        thing.applyRestoration();
        thing.applyRestoration();

        assertThat(thing.isDeleted()).isFalse();
    }
}
