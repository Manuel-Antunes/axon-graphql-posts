package dev.manuelantunes.axonposts.infrastructure.persistence.panache;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import dev.manuelantunes.axonposts.domain.user.Account;
import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Adapter: implementa a porta {@link UserRepository} com Hibernate ORM + Panache sobre PostgreSQL.
 * <p>
 * Devolve sempre o tipo <b>concreto</b> — um id de autor volta como {@code Author} — porque a herança
 * {@code JOINED} faz toda consulta ser polimórfica. É isso que torna o {@code instanceof} do
 * {@code CurrentUser} confiável: quem decide o tipo é o banco, através da tabela {@code authors}, não uma
 * flag no token.
 */
@ApplicationScoped
public class PanacheUserRepository implements UserRepository {

    private final UserPanache users;

    PanacheUserRepository(UserPanache users) {
        this.users = users;
    }

    @Override
    @Transactional
    public void save(User user) {
        users.getEntityManager().merge(user);
    }

    @Override
    @Transactional
    public Optional<User> findById(UserId userId) {
        return users.byIdWithAccounts(userId);
    }

    /**
     * Devolve o {@code User} e não a {@code Account} de propósito — {@code Account} é entidade dentro
     * deste agregado, e quem se carrega é a raiz.
     */
    @Override
    @Transactional
    public Optional<User> findByAccount(AuthProvider provider, String subject) {
        return users.accountBy(provider, subject).map(Account::user);
    }

    @Override
    @Transactional
    public Optional<UserId> findDeletedUserIdByAccount(AuthProvider provider, String subject) {
        return users.deletedUserIdByAccount(provider, subject).map(UserId::of);
    }

    /** No máximo um: {@code supersede} recusa substituir duas vezes, e um autor não é substituído. */
    @Override
    @Transactional
    public Optional<User> findSupersededByEmail(Email email) {
        return users.supersededByEmail(email.value());
    }

    /** O {@link Email} normaliza para minúsculas no construtor, então a busca é exata de propósito. */
    @Override
    @Transactional
    public Optional<User> findByEmail(Email email) {
        return users.activeByEmail(email.value());
    }

    @Override
    @Transactional
    public List<User> findAllById(Collection<UserId> userIds) {
        return userIds.isEmpty() ? List.of() : users.withAccountsByIds(userIds);
    }

    @Override
    @Transactional
    public void restore(UserId userId) {
        users.undelete(userId);
    }

    @Override
    @Transactional
    public boolean isEmpty() {
        return users.count() == 0;
    }
}
