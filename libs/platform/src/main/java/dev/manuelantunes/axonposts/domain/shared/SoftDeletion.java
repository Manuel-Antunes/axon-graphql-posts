package dev.manuelantunes.axonposts.domain.shared;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.Instant;
import java.util.Objects;

@Embeddable
public class SoftDeletion {
    public static final String COLUMN = "deleted_at";

    @Column(name = COLUMN)
    private Instant deletedAt;

    public SoftDeletion() {
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Instant at() {
        return deletedAt;
    }

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
