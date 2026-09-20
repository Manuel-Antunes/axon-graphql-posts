package dev.manuelantunes.axonposts.infrastructure.persistence.user;

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
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class PanacheUserRepository implements UserRepository {
    private final EntityManager em;

    PanacheUserRepository(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional
    public void save(User user) {
        em.merge(user);
    }

    @Override
    @Transactional
    public Optional<User> findById(UserId userId) {
        return em.createQuery("select u from User u left join fetch u.accounts where u.id = :id", User.class)
                .setParameter("id", userId)
                .getResultStream()
                .findFirst();
    }

    @Override
    @Transactional
    public Optional<User> findByAccount(AuthProvider provider, String subject) {
        return em.createQuery("select a from Account a join fetch a.user u left join fetch u.accounts "
                        + "where a.provider = :provider and a.subject = :subject "
                        + "and u.supersededBy.value is null", Account.class)
                .setParameter("provider", provider)
                .setParameter("subject", subject)
                .getResultStream()
                .findFirst()
                .map(Account::user);
    }

    @Override
    @Transactional
    public Optional<UserId> findDeletedUserIdByAccount(AuthProvider provider, String subject) {
        Optional<String> userId = em.createNativeQuery(
                        "select a.user_id from accounts a join users u on u.id = a.user_id "
                        + "where a.provider = :provider and a.subject = :subject "
                        + "and u.deleted_at is not null", String.class)
                .setParameter("provider", provider.name())
                .setParameter("subject", subject)
                .getResultStream()
                .map(String.class::cast)
                .findFirst();
        return userId.map(UserId::of);
    }

    @Override
    @Transactional
    public Optional<User> findSupersededByEmail(Email email) {
        return em.createQuery("select u from User u left join fetch u.accounts "
                        + "where u.email.value = :email and u.supersededBy.value is not null", User.class)
                .setParameter("email", email.value())
                .getResultStream()
                .findFirst();
    }

    @Override
    @Transactional
    public Optional<User> findByEmail(Email email) {
        return em.createQuery("select u from User u left join fetch u.accounts "
                        + "where u.email.value = :email and u.supersededBy.value is null", User.class)
                .setParameter("email", email.value())
                .getResultStream()
                .findFirst();
    }

    @Override
    @Transactional
    public List<User> findAllById(Collection<UserId> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select u from User u left join fetch u.accounts where u.id in :ids", User.class)
                .setParameter("ids", userIds)
                .getResultList();
    }

    @Override
    @Transactional
    public void restore(UserId userId) {
        em.createNativeQuery("update users set deleted_at = null where id = :id")
                .setParameter("id", userId.value())
                .executeUpdate();
    }

    @Override
    @Transactional
    public boolean isEmpty() {
        return em.createQuery("select count(u) from User u", Long.class).getSingleResult() == 0;
    }
}
