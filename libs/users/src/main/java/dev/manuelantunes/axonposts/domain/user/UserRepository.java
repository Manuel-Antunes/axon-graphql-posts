package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository {
    void save(User user);

    Optional<User> findById(UserId userId);

    Optional<User> findByAccount(AuthProvider provider, String subject);

    Optional<UserId> findDeletedUserIdByAccount(AuthProvider provider, String subject);

    Optional<User> findSupersededByEmail(Email email);

    Optional<User> findByEmail(Email email);

    List<User> findAllById(Collection<UserId> userIds);

    void restore(UserId userId);

    boolean isEmpty();
}
