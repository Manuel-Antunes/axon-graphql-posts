package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.user.vo.AccountId;
import dev.manuelantunes.axonposts.domain.user.vo.PasswordHash;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Entity
@Table(
        name = "accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_accounts_provider_subject",
                columnNames = {"provider", "subject"})
)
public class Account implements Authenticatable {
    @EmbeddedId
    @AttributeOverride(name = "value", column = @Column(name = "id", length = 36, nullable = false))
    private AccountId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 32, nullable = false)
    private AuthProvider provider;

    @Column(name = "subject", length = 255, nullable = false)
    private String subject;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "password_hash"))
    private PasswordHash passwordHash;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    protected Account() {
    }

    Account(AccountId id, User user, AuthProvider provider, String subject,
            PasswordHash passwordHash, Instant linkedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.user = Objects.requireNonNull(user, "user");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.subject = Objects.requireNonNull(subject, "subject").strip();
        this.passwordHash = passwordHash;
        this.linkedAt = Objects.requireNonNull(linkedAt, "linkedAt");
    }

    @Override
    public AuthProvider provider() {
        return provider;
    }

    @Override
    public String subject() {
        return subject;
    }

    @Override
    public Optional<PasswordHash> passwordHash() {
        return Optional.ofNullable(passwordHash);
    }

    public AccountId id() {
        return id;
    }

    public User user() {
        return user;
    }

    public Instant linkedAt() {
        return linkedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Account account && id != null && id.equals(account.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return provider + ":" + subject;
    }
}
