package dev.manuelantunes.axonposts.infrastructure.persistence.sqlite;

import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repositório Spring Data JPA dos usuários. Declarado sobre {@link User}, a raiz da hierarquia — como a
 * herança é {@code JOINED}, toda consulta daqui é polimórfica: o Hibernate faz o {@code left join} com
 * {@code authors} e instancia {@code Author} quando a linha filha existe.
 */
interface SpringDataUserRepository extends JpaRepository<User, UserId> {

    Optional<User> findByEmailValue(String email);

    /** Nativa, para escapar do {@code @SQLRestriction} — ver {@code UserRepository.restore}. */
    @Modifying
    @Query(value = "update users set deleted_at = null where id = :id", nativeQuery = true)
    int restoreById(@Param("id") String id);
}
