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

    /**
     * INSERT direto na tabela filha — ver {@code UserRepository.promoteToAuthor} sobre por que não dá
     * para fazer isto pelo JPA.
     * <p>
     * O {@code clear()} depois não é zelo: a instância de {@code User} que estiver no contexto de
     * persistência continuaria sendo um {@code User} para o Hibernate, porque o tipo é resolvido no
     * momento da carga. Sem limpar, a leitura seguinte na mesma transação devolveria o objeto antigo e o
     * {@code instanceof Author} diria não logo depois de a linha ter sido criada.
     */
    @Override
    @Transactional
    public void promoteToAuthor(UserId userId, String bio) {
        entityManager.createNativeQuery("insert into authors (id, bio) values (:id, :bio)")
                .setParameter("id", userId.value())
                .setParameter("bio", bio)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
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
