package dev.manuelantunes.axonposts.infrastructure.persistence.jpa;

import dev.manuelantunes.axonposts.domain.user.Account;
import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.vo.AccountId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repositório Spring Data das credenciais.
 * <p>
 * Existe só para servir o {@code findByAccount} do {@code UserRepository}: {@code Account} é entidade
 * dentro do agregado {@code User}, então este repositório não é exposto ao domínio — é detalhe do
 * adapter.
 */
interface SpringDataAccountRepository extends JpaRepository<Account, AccountId> {

    /**
     * O {@code join fetch} do usuário é o que importa aqui: sem ele, resolver a conta e depois o dono
     * seriam duas consultas em <b>toda</b> requisição autenticada.
     */
    @Query("select a from Account a join fetch a.user where a.provider = :provider and a.subject = :subject")
    Optional<Account> findByProviderAndSubject(@Param("provider") AuthProvider provider,
                                               @Param("subject") String subject);
}
