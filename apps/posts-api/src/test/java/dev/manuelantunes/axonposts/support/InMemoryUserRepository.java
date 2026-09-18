package dev.manuelantunes.axonposts.support;

import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repositório de usuários em memória.
 * <p>
 * Guarda o objeto como ele foi salvo, então um {@code Author} volta {@code Author} — que é justamente a
 * propriedade que os testes do {@code CreatePostCommand} exercitam: lá o {@code instanceof} decide se o
 * command aceita ou recusa.
 */
public final class InMemoryUserRepository implements UserRepository {

    private final List<User> users = new ArrayList<>();

    public InMemoryUserRepository(User... initial) {
        users.addAll(List.of(initial));
    }

    @Override
    public void save(User user) {
        users.add(user);
    }

    @Override
    public Optional<User> findById(UserId userId) {
        return users.stream().filter(user -> user.id().equals(userId)).findFirst();
    }

    @Override
    public Optional<User> findByAccount(AuthProvider provider, String subject) {
        return users.stream()
                .filter(user -> !user.isSuperseded())
                .filter(user -> user.accountFor(provider)
                        .filter(account -> account.subject().equals(subject))
                        .isPresent())
                .findFirst();
    }

    /** Exclui os encerrados, como o adapter real — dois usuários podem ter o mesmo e-mail. */
    @Override
    public Optional<User> findByEmail(Email email) {
        return users.stream()
                .filter(user -> user.email().equals(email) && !user.isSuperseded())
                .findFirst();
    }

    @Override
    public List<User> findAllById(Collection<UserId> userIds) {
        return users.stream().filter(user -> userIds.contains(user.id())).toList();
    }

    /** Sem filtro em memória, nada a desfazer — ver o mesmo método no {@code InMemoryPostRepository}. */
    /** Sem {@code @SQLRestriction} em memória, apagado continua visível — daí a busca ser a mesma. */
    @Override
    public Optional<UserId> findDeletedUserIdByAccount(AuthProvider provider, String subject) {
        return users.stream()
                .filter(User::isDeleted)
                .filter(user -> user.accountFor(provider)
                        .filter(account -> account.subject().equals(subject))
                        .isPresent())
                .map(User::id)
                .findFirst();
    }

    @Override
    public Optional<User> findSupersededByEmail(Email email) {
        return users.stream()
                .filter(user -> user.isSuperseded() && user.email().equals(email))
                .findFirst();
    }

    @Override
    public void restore(UserId userId) {
        // no-op
    }

    @Override
    public boolean isEmpty() {
        return users.isEmpty();
    }
}
