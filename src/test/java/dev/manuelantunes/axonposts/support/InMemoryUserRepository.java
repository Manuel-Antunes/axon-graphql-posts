package dev.manuelantunes.axonposts.support;

import dev.manuelantunes.axonposts.domain.user.Author;
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
                .filter(user -> user.accountFor(provider)
                        .filter(account -> account.subject().equals(subject))
                        .isPresent())
                .findFirst();
    }

    /**
     * No adapter real isto é um INSERT na tabela filha, porque o JPA não muda o tipo de uma linha. Aqui
     * a instância é trocada por um {@code Author} com o mesmo estado — o efeito observável é o mesmo, que
     * é o que o teste do provisionamento verifica: a leitura seguinte devolve um {@code Author}.
     */
    @Override
    public void promoteToAuthor(UserId userId, String bio) {
        findById(userId).ifPresent(user -> {
            if (user.isAuthor()) {
                return;
            }
            Author promoted = Author.register(user.id(), user.email().value(), user.name().value(),
                    bio, user.createdAt());
            user.accounts().forEach(account ->
                    promoted.link(account.provider(), account.subject(), account.linkedAt()));
            users.remove(user);
            users.add(promoted);
        });
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return users.stream().filter(user -> user.email().equals(email)).findFirst();
    }

    @Override
    public List<User> findAllById(Collection<UserId> userIds) {
        return users.stream().filter(user -> userIds.contains(user.id())).toList();
    }

    /** Sem filtro em memória, nada a desfazer — ver o mesmo método no {@code InMemoryPostRepository}. */
    @Override
    public void restore(UserId userId) {
        // no-op
    }

    @Override
    public boolean isEmpty() {
        return users.isEmpty();
    }
}
