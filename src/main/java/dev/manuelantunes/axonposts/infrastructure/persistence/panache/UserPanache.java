package dev.manuelantunes.axonposts.infrastructure.persistence.panache;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import dev.manuelantunes.axonposts.domain.user.Account;
import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * O "driver" do PostgreSQL para {@link User}. Ver {@link PostPanache} sobre por que são duas classes.
 * <p>
 * Declarado sobre a raiz da hierarquia — como a herança é {@code JOINED}, toda consulta daqui é
 * polimórfica: o Hibernate faz o {@code left join} com {@code authors} e instancia {@code Author} quando
 * a linha filha existe. É isso que torna o {@code instanceof} do {@code CurrentUser} confiável.
 */
@ApplicationScoped
class UserPanache implements PanacheRepositoryBase<User, UserId> {

    /** Um usuário, com as contas. A coleção é {@code LAZY} e quem chama a usa fora da transação. */
    Optional<User> byIdWithAccounts(UserId userId) {
        return getEntityManager()
                .createQuery("select u from User u left join fetch u.accounts where u.id = :id", User.class)
                .setParameter("id", userId)
                .getResultStream()
                .findFirst();
    }

    /**
     * O usuário dono de uma credencial — <b>a</b> consulta do caminho autenticado.
     *
     * <h3>Os dois {@code join fetch}</h3>
     * O primeiro, do usuário, evita que resolver a conta e depois o dono sejam duas consultas em toda
     * requisição autenticada. O segundo, das contas do usuário, é o que faltava quando a view passou a
     * sair completa: navegar de {@code Account} para {@code User} traz o dono, mas deixa a coleção
     * {@code accounts} dele por inicializar — e ela é {@code LAZY}. O {@code UserViewMapper} lê essa
     * coleção fora da transação, e sem este fetch estourava {@code LazyInitializationException} no
     * caminho mais quente do sistema.
     * <p>
     * {@code u.supersededBy.value is null} exclui os agregados encerrados por promoção — a credencial já
     * foi solta por eles, mas o filtro deixa a intenção explícita na consulta.
     */
    Optional<Account> accountBy(AuthProvider provider, String subject) {
        return getEntityManager()
                .createQuery("select a from Account a join fetch a.user u left join fetch u.accounts "
                        + "where a.provider = :provider and a.subject = :subject "
                        + "and u.supersededBy.value is null", Account.class)
                .setParameter("provider", provider)
                .setParameter("subject", subject)
                .getResultStream()
                .findFirst();
    }

    /**
     * Nativa porque precisa <b>ver</b> o que o {@code @SQLRestriction} esconde: qualquer consulta pelo
     * mapeamento da entidade traria o filtro junto e nunca acharia um usuário apagado.
     */
    Optional<String> deletedUserIdByAccount(AuthProvider provider, String subject) {
        return getEntityManager()
                .createNativeQuery("select a.user_id from accounts a join users u on u.id = a.user_id "
                        + "where a.provider = :provider and a.subject = :subject "
                        + "and u.deleted_at is not null", String.class)
                .setParameter("provider", provider.name())
                .setParameter("subject", subject)
                .getResultStream()
                .map(String.class::cast)
                .findFirst();
    }

    /**
     * Encerrado por promoção — <b>não</b> apagado. As duas coisas são independentes: o
     * {@code @SQLRestriction} filtra {@code deleted_at}, e um usuário substituído continua visível.
     */
    Optional<User> supersededByEmail(String email) {
        return getEntityManager()
                .createQuery("select u from User u left join fetch u.accounts "
                        + "where u.email.value = :email and u.supersededBy.value is not null", User.class)
                .setParameter("email", email)
                .getResultStream()
                .findFirst();
    }

    /**
     * O usuário <b>ativo</b> com este e-mail.
     *
     * <h3>{@code supersededBy is null} não é detalhe</h3>
     * Desde que a promoção passou a encerrar um agregado e abrir outro, <b>dois usuários podem ter o
     * mesmo e-mail</b>: o leitor encerrado e o autor que o substituiu. Sem o filtro esta consulta
     * devolveria duas linhas — ou, pior, o leitor morto.
     */
    Optional<User> activeByEmail(String email) {
        return getEntityManager()
                .createQuery("select u from User u left join fetch u.accounts "
                        + "where u.email.value = :email and u.supersededBy.value is null", User.class)
                .setParameter("email", email)
                .getResultStream()
                .findFirst();
    }

    /**
     * Vários usuários <b>com as contas</b>, numa consulta só — o lote do campo {@code Post.author}.
     * <p>
     * Sem {@code distinct}: o Hibernate 6 deduplica os roots sozinho, e escrever {@code distinct} iria
     * parar no SQL e mudaria o plano à toa.
     */
    List<User> withAccountsByIds(Collection<UserId> ids) {
        return getEntityManager()
                .createQuery("select u from User u left join fetch u.accounts where u.id in :ids", User.class)
                .setParameter("ids", ids)
                .getResultList();
    }

    /** Nativa, para escapar do {@code @SQLRestriction} — ver {@code UserRepository.restore}. */
    int undelete(UserId userId) {
        return getEntityManager()
                .createNativeQuery("update users set deleted_at = null where id = :id")
                .setParameter("id", userId.value())
                .executeUpdate();
    }
}
