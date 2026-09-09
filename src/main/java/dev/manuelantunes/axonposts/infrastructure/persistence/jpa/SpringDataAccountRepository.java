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
     * <p>
     * O segundo {@code join fetch}, das contas do usuário, é o que faltava quando a view passou a sair
     * completa: navegar de {@code Account} para {@code User} traz o dono, mas deixa a coleção
     * {@code accounts} dele por inicializar — e ela é {@code LAZY}. O {@code UserViewMapper} lê essa
     * coleção fora da transação, e sem este fetch estourava {@code LazyInitializationException} no caminho
     * mais quente do sistema: toda requisição autenticada de quem já tem conta.
     * <p>
     * {@code u.supersededBy.value is null} exclui os agregados encerrados por promoção — a credencial já
     * foi solta por eles, mas o filtro deixa a intenção explícita na consulta.
     */
    @Query("select a from Account a join fetch a.user u left join fetch u.accounts "
            + "where a.provider = :provider and a.subject = :subject and u.supersededBy.value is null")
    Optional<Account> findByProviderAndSubject(@Param("provider") AuthProvider provider,
                                               @Param("subject") String subject);
}
