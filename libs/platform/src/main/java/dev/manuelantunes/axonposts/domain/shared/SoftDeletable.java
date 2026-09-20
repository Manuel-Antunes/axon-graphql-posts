package dev.manuelantunes.axonposts.domain.shared;

import java.time.Instant;

public interface SoftDeletable {
    SoftDeletion softDeletion();

    Object identity();

    boolean isDeleted();

    Instant deletedAt();

    void delete(Instant now);

    void restore();

    void applyDeletion(Instant at);

    void applyRestoration();
}
