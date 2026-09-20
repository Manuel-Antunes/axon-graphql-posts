package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;
import dev.manuelantunes.axonposts.domain.shared.EmbeddableSoftDeletable;
import dev.manuelantunes.axonposts.domain.shared.SoftDeletable;
import dev.manuelantunes.axonposts.domain.shared.SoftDeletion;
import dev.manuelantunes.axonposts.domain.user.event.AccountLinkedEvent;
import dev.manuelantunes.axonposts.domain.user.event.UserDeletedEvent;
import dev.manuelantunes.axonposts.domain.user.event.UserRegisteredEvent;
import dev.manuelantunes.axonposts.domain.user.event.UserRestoredEvent;
import dev.manuelantunes.axonposts.domain.user.event.UserSupersededEvent;
import dev.manuelantunes.axonposts.domain.user.exception.InvalidUserException;
import dev.manuelantunes.axonposts.domain.user.vo.AccountId;
import dev.manuelantunes.axonposts.domain.user.vo.DisplayName;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.PasswordHash;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Entity
@Table(name = "users")
@Inheritance(strategy = InheritanceType.JOINED)
@EventSourcedEntity(tagKey = User.TAG_KEY, concreteTypes = {Reader.class, Author.class})
@SQLRestriction(User.ALIVE)
@SQLDelete(sql = "update users set deleted_at = current_timestamp where id = ?")
public abstract class User implements
        SoftDeletable,
        EmbeddableSoftDeletable {
    public static final String TAG_KEY = "userId";

    public static final String ALIVE = SoftDeletion.COLUMN + " is null";

    @EmbeddedId
    @AttributeOverride(name = "value", column = @Column(name = "id", length = 36, nullable = false))
    private UserId id;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "email", length = Email.MAX_LENGTH, nullable = false))
    private Email email;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "name", length = DisplayName.MAX_LENGTH, nullable = false))
    private DisplayName name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "superseded_by", length = 36))
    private UserId supersededBy;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "supersedes", length = 36))
    private UserId supersedes;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<Account> accounts = new LinkedHashSet<>();

    @Embedded
    private SoftDeletion softDeletion = new SoftDeletion();

    protected User() {
    }

    protected User(UserRegisteredEvent event) {
        this.id = Objects.requireNonNull(event.userId(), "userId");
        this.email = Email.of(event.email());
        this.name = DisplayName.of(event.name());
        this.createdAt = event.occurredAt();
        this.supersedes = event.supersedes();
    }

    public static User register(UserId id, String email, String name, boolean author, String bio,
                                UserId supersedes, Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        UserRegisteredEvent event = new UserRegisteredEvent(
                id, Email.of(email).value(), DisplayName.of(name).value(), author, bio, supersedes, now);

        events.raise(event);
        return create(event);
    }

    public Account link(AuthProvider provider, String subject, PasswordHash passwordHash,
                        Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(events, "events");
        if (isLinkedTo(provider)) {
            throw new InvalidUserException("usuário " + id + " já tem conta em " + provider);
        }

        AccountLinkedEvent event = new AccountLinkedEvent(
                id, AccountId.newId().value(), provider, subject,
                passwordHash == null ? null : passwordHash.value(), now);

        events.raise(event);
        on(event);
        return accountFor(provider).orElseThrow();
    }

    public Account link(AuthProvider provider, String subject, Instant now, DomainEventPublisher events) {
        return link(provider, subject, null, now, events);
    }

    public User supersede(UserId successor, Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(successor, "successor");
        Objects.requireNonNull(events, "events");
        if (isAuthor()) {
            throw new InvalidUserException("um autor não é substituído: " + id);
        }
        if (isSuperseded()) {
            throw new InvalidUserException("usuário " + id + " já foi substituído por " + supersededBy);
        }

        UserSupersededEvent event = new UserSupersededEvent(id, successor, now);
        events.raise(event);
        on(event);
        return this;
    }

    public User delete(Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        delete(now);

        UserDeletedEvent event = new UserDeletedEvent(id, now);
        events.raise(event);
        on(event);
        return this;
    }

    public User restore(Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        restore();

        UserRestoredEvent event = new UserRestoredEvent(id, now);
        events.raise(event);
        on(event);
        return this;
    }

    @EntityCreator
    public static User create(UserRegisteredEvent event) {
        return event.author() ? new Author(event) : new Reader(event);
    }

    @EventSourcingHandler
    public void on(AccountLinkedEvent event) {
        AccountId accountId = AccountId.of(event.accountId());
        if (accounts.stream().anyMatch(account -> account.id().equals(accountId))) {
            return;
        }
        accounts.add(new Account(
                accountId, this, event.provider(), event.subject(),
                event.passwordHash() == null ? null : PasswordHash.of(event.passwordHash()),
                event.occurredAt()));
    }

    @EventSourcingHandler
    public void on(UserSupersededEvent event) {
        this.supersededBy = event.supersededBy();
        this.accounts.clear();
    }

    @EventSourcingHandler
    public void on(UserDeletedEvent event) {
        applyDeletion(event.occurredAt());
    }

    @EventSourcingHandler
    public void on(UserRestoredEvent event) {
        applyRestoration();
    }

    public Optional<Account> accountFor(AuthProvider provider) {
        return accounts.stream().filter(account -> account.provider() == provider).findFirst();
    }

    public boolean isLinkedTo(AuthProvider provider) {
        return accountFor(provider).isPresent();
    }

    public Set<Account> accounts() {
        return Set.copyOf(accounts);
    }

    public Set<Role> roles() {
        return EnumSet.of(Role.USER);
    }

    public boolean isAuthor() {
        return this instanceof Author;
    }

    public boolean isSuperseded() {
        return supersededBy != null;
    }

    public UserId supersededBy() {
        return supersededBy;
    }

    public UserId supersedes() {
        return supersedes;
    }

    public boolean isReference() {
        return createdAt == null;
    }

    public UserId id() {
        return id;
    }

    public Email email() {
        return email;
    }

    public DisplayName name() {
        return name;
    }

    public Instant createdAt() {
        return createdAt;
    }

    protected void initReference(UserId id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public SoftDeletion softDeletion() {
        if (softDeletion == null) {
            softDeletion = new SoftDeletion();
        }
        return softDeletion;
    }

    @Override
    public Object identity() {
        return id;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof User user && id != null && id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return name == null ? String.valueOf(id) : name.value();
    }
}
