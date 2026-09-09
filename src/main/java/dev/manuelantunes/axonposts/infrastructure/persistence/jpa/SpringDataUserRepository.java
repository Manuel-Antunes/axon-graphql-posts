package dev.manuelantunes.axonposts.infrastructure.persistence.jpa;

import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import dev.manuelantunes.axonposts.domain.user.vo.UserId;

/**
 * Repositório Spring Data JPA dos usuários. Declarado sobre {@link User}, a raiz da hierarquia — como a
 * herança é {@code JOINED}, toda consulta daqui é polimórfica: o Hibernate faz o {@code left join} com
 * {@code authors} e instancia {@code Author} quando a linha filha existe.
 */
interface SpringDataUserRepository extends JpaRepository<User, UserId> {

    /**
     * Com as contas: é o caminho do login e do account linking, onde saber por quais provedores o usuário
     * entra é o ponto. As consultas abaixo trazem {@code accounts} por {@code join fetch} porque a coleção
     * é {@code LAZY} e quem chama costuma usá-la fora da transação.
     *
     * <h3>{@code superseded_by is null} não é detalhe</h3>
     * Desde que a promoção passou a encerrar um agregado e abrir outro, <b>dois usuários podem ter o mesmo
     * e-mail</b>: o leitor encerrado e o autor que o substituiu. Por isso a coluna {@code email} deixou de
     * ser {@code unique}, e por isso esta consulta precisa excluir os encerrados — sem o filtro ela
     * devolveria duas linhas e o {@code Optional} estouraria, ou pior, devolveria o leitor morto.
     * <p>
     * O predicado é sobre {@code supersededBy.value} e não sobre {@code supersededBy}: um
     * {@code @Embedded} com todas as colunas nulas não é confiavelmente nulo em JPQL.
     */
    @Query("select u from User u left join fetch u.accounts "
            + "where u.email.value = :email and u.supersededBy.value is null")
    Optional<User> findByEmailValue(@Param("email") String email);

    @Query("select u from User u left join fetch u.accounts where u.id = :id")
    Optional<User> findByIdWithAccounts(@Param("id") UserId id);

    /**
     * Encerrado por promoção — <b>não</b> apagado. As duas coisas são independentes: o
     * {@code @SQLRestriction} filtra {@code deleted_at}, e um usuário substituído continua visível.
     */
    @Query("select u from User u left join fetch u.accounts "
            + "where u.email.value = :email and u.supersededBy.value is not null")
    List<User> findSupersededByEmailValue(@Param("email") String email);

    /**
     * Nativa porque precisa <b>ver</b> o que o {@code @SQLRestriction} esconde: qualquer consulta pelo
     * mapeamento da entidade traria o filtro junto e nunca acharia um usuário apagado.
     */
    @Query(value = "select a.user_id from accounts a join users u on u.id = a.user_id "
            + "where a.provider = :provider and a.subject = :subject and u.deleted_at is not null",
            nativeQuery = true)
    Optional<String> findDeletedUserIdByAccount(@Param("provider") String provider,
                                                @Param("subject") String subject);

    /**
     * Vários usuários <b>com as contas</b>, numa consulta só.
     * <p>
     * O {@code findAllById} herdado não serve para o lote: a coleção {@code accounts} é {@code EAGER},
     * então o Hibernate a honra com um SELECT <b>por usuário</b> depois de trazer a lista. Com um autor a
     * diferença passa despercebida; com N autores numa resposta GraphQL é um N+1 — exatamente o que os
     * DataLoaders desta aplicação existem para evitar.
     * <p>
     * Sem {@code distinct}: o Hibernate 6 deduplica os roots sozinho, e escrever {@code distinct} iria
     * parar no SQL e mudaria o plano à toa.
     */
    @Query("select u from User u left join fetch u.accounts where u.id in :ids")
    List<User> findAllWithAccountsByIdIn(@Param("ids") Collection<UserId> ids);

    /** Nativa, para escapar do {@code @SQLRestriction} — ver {@code UserRepository.restore}. */
    @Modifying
    @Query(value = "update users set deleted_at = null where id = :id", nativeQuery = true)
    int restoreById(@Param("id") String id);
}
