package dev.manuelantunes.axonposts.infrastructure.persistence.sqlite;

import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Adapter: implementa a porta {@link UserRepository} com Spring Data JPA sobre SQLite. */
@Repository
public class JpaUserRepository implements UserRepository {

    private final SpringDataUserRepository repository;

    public JpaUserRepository(SpringDataUserRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(User user) {
        repository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findById(UserId userId) {
        return repository.findById(userId);
    }

    /** O {@code Email} normaliza para minúsculas no construtor, então a busca é exata de propósito. */
    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByEmail(Email email) {
        return repository.findByEmailValue(email.value());
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> findAllById(Collection<UserId> userIds) {
        return userIds.isEmpty() ? List.of() : repository.findAllById(userIds);
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
