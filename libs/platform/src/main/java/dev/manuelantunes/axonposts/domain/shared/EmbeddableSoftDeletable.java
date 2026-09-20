package dev.manuelantunes.axonposts.domain.shared;

import java.time.Instant;

public interface EmbeddableSoftDeletable extends SoftDeletable {
    default boolean isDeleted() {
        return softDeletion().isDeleted();
    }

    default Instant deletedAt() {
        return softDeletion().at();
    }

    default void delete(Instant now) {
        if (isDeleted()) {
            throw new AlreadyDeletedException(identity());
        }
        softDeletion().delete(now);
    }

    default void restore() {
        if (!isDeleted()) {
            throw new NotDeletedException(identity());
        }
        softDeletion().restore();
    }

    default void applyDeletion(Instant at) {
        softDeletion().delete(at);
    }

    default void applyRestoration() {
        softDeletion().restore();
    }
}
