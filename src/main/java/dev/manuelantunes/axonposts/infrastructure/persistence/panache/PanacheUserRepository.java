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
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Adapter: implementa a porta {@link UserRepository} com Hibernate ORM sobre PostgreSQL.
 * Ver {@link PanachePostRepository} sobre por que o {@code EntityManager} vem pelo construtor.
 * <p>
 * Devolve sempre o tipo <b>concreto</b> — um id de autor volta como {@code Author} — porque a herança
 * {@code JOINED} faz toda consulta ser polimórfica: o Hibernate faz o {@code left join} com
 * {@code authors} e instancia {@code Author} quando a linha filha existe. É isso que torna o
 * {@code instanceof} do {@code CurrentUser} confiável: quem decide o tipo é o banco, através da tabela
 * {@code authors}, não uma flag no token.
 */
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

    /** Um usuário, com as contas. A coleção é {@code LAZY} e quem chama a usa fora da transação. */
    @Override
    @Transactional
    public Optional<User> findById(UserId userId) {
        return em.createQuery("select u from User u left join fetch u.accounts where u.id = :id", User.class)
                .setParameter("id", userId)
                .getResultStream()
                .findFirst();
    }

    /**
     * O usuário dono de uma credencial — <b>a</b> consulta do caminho autenticado. Devolve o {@code User}
     * e não a {@code Account} de propósito: {@code Account} é entidade dentro deste agregado, e quem se
     * carrega é a raiz.
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

    /**
     * Nativa porque precisa <b>ver</b> o que o {@code @SQLRestriction} esconde: qualquer consulta pelo
     * mapeamento da entidade traria o filtro junto e nunca acharia um usuário apagado.
     */
    @Override
    @Transactional
    public Optional<UserId> findDeletedUserIdByAccount(AuthProvider provider, String subject) {
        // A consulta nativa devolve um Query cru, então o id sai como String e vira UserId num passo
        // separado — encadear direto perderia o tipo no apagamento.
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

    /**
     * Encerrado por promoção — <b>não</b> apagado. As duas coisas são independentes: o
     * {@code @SQLRestriction} filtra {@code deleted_at}, e um usuário substituído continua visível.
     * <p>
     * No máximo um: {@code supersede} recusa substituir duas vezes, e um autor não é substituído.
     */
    @Override
    @Transactional
    public Optional<User> findSupersededByEmail(Email email) {
        return em.createQuery("select u from User u left join fetch u.accounts "
                        + "where u.email.value = :email and u.supersededBy.value is not null", User.class)
                .setParameter("email", email.value())
                .getResultStream()
                .findFirst();
    }

    /**
     * O usuário <b>ativo</b> com este e-mail. O {@link Email} normaliza para minúsculas no construtor,
     * então a busca é exata de propósito.
     *
     * <h3>{@code supersededBy is null} não é detalhe</h3>
     * Desde que a promoção passou a encerrar um agregado e abrir outro, <b>dois usuários podem ter o
     * mesmo e-mail</b>: o leitor encerrado e o autor que o substituiu. Sem o filtro esta consulta
     * devolveria duas linhas — ou, pior, o leitor morto.
     */
    @Override
    @Transactional
    public Optional<User> findByEmail(Email email) {
        return em.createQuery("select u from User u left join fetch u.accounts "
                        + "where u.email.value = :email and u.supersededBy.value is null", User.class)
                .setParameter("email", email.value())
                .getResultStream()
                .findFirst();
    }

    /**
     * Vários usuários <b>com as contas</b>, numa consulta só — o lote do campo {@code Post.author}.
     * <p>
     * Sem {@code distinct}: o Hibernate 6 deduplica os roots sozinho, e escrever {@code distinct} iria
     * parar no SQL e mudaria o plano à toa.
     */
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

    /** Nativa, para escapar do {@code @SQLRestriction} — ver {@code UserRepository.restore}. */
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
