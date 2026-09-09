package dev.manuelantunes.axonposts.infrastructure.persistence.jpa;

import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Adapter: implementa a porta {@link UserRepository} com Spring Data JPA sobre PostgreSQL. */
@Repository
public class JpaUserRepository implements UserRepository {

    private final SpringDataUserRepository repository;
    private final SpringDataAccountRepository accounts;

    @PersistenceContext
    private EntityManager entityManager;

    public JpaUserRepository(SpringDataUserRepository repository, SpringDataAccountRepository accounts) {
        this.repository = repository;
        this.accounts = accounts;
    }

    @Override
    @Transactional
    public void save(User user) {
        repository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findById(UserId userId) {
        return repository.findByIdWithAccounts(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByAccount(AuthProvider provider, String subject) {
        return accounts.findByProviderAndSubject(provider, subject).map(account -> account.user());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserId> findDeletedUserIdByAccount(AuthProvider provider, String subject) {
        return repository.findDeletedUserIdByAccount(provider.name(), subject).map(UserId::of);
    }

    /** No máximo um: {@code supersede} recusa substituir duas vezes, e um autor não é substituído. */
    @Override
    @Transactional(readOnly = true)
    public Optional<User> findSupersededByEmail(Email email) {
        return repository.findSupersededByEmailValue(email.value()).stream().findFirst();
    }

    /** O {@code Email} normaliza para minúsculas no construtor, então a busca é exata de propósito. */
    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByEmail(Email email) {
        return repository.findByEmailValue(email.value());
    }

    /**
     * Usa a consulta com {@code join fetch} das contas, e não o {@code findAllById} herdado — ver o
     * javadoc de {@code findAllWithAccountsByIdIn} sobre o N+1 que isso evita.
     */
    @Override
    @Transactional(readOnly = true)
    public List<User> findAllById(Collection<UserId> userIds) {
        return userIds.isEmpty() ? List.of() : repository.findAllWithAccountsByIdIn(userIds);
    }

    @Override
    @Transactional
    public void restore(UserId userId) {
        repository.restoreById(userId.value());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return repository.count() == 0;
    }
}
