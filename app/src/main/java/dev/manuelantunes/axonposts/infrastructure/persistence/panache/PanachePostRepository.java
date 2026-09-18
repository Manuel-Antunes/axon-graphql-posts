package dev.manuelantunes.axonposts.infrastructure.persistence.panache;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Adapter: implementa a porta {@link PostRepository} com Hibernate ORM sobre PostgreSQL.
 * <p>
 * Ficou fino porque não há tradução a fazer — {@link Post} é a entidade JPA. O que sobra é a fronteira
 * transacional, as consultas e o agrupamento dos resultados em lote.
 *
 * <h2>Por que o {@code EntityManager} no construtor, e não {@code PanacheRepositoryBase}</h2>
 * Herdar de {@code PanacheRepositoryBase} <b>obrigava a duas classes</b>: ela declara
 * {@code Post findById(PostId)} e a porta declara {@code Optional<Post> findById(PostId)} — mesma
 * assinatura, retornos incompatíveis, e o compilador recusa a classe que tente ser as duas coisas. A
 * saída era um repositório Panache pacote-visível ao lado, só para hospedar as consultas.
 * <p>
 * O preço não se justificava: destes 52 métodos públicos, o adapter usava {@code findByIdOptional()} e
 * {@code getEntityManager()} — e o {@code EntityManager} se injeta direto. Todas as consultas já eram
 * JPQL escrito à mão. Com a porta injetada no construtor a classe volta a ser uma, e a API do Panache
 * deixa de existir na superfície do bean que a aplicação conhece como {@code PostRepository}.
 *
 * <h2>Por que {@code merge} e não {@code persist}</h2>
 * A entidade que chega aqui vem <b>reconstituída dos eventos pelo Axon</b>, não de uma sessão do
 * Hibernate: ela é sempre <i>detached</i>, exista a linha ou não. O {@code persist()} falha nesse caso
 * ("detached entity passed to persist"), e é por isso que {@link #save} vai direto ao
 * {@code EntityManager.merge} — que é exatamente a semântica do {@code JpaRepository.save} da versão
 * Spring, agora explícita em vez de escondida numa interface gerada.
 *
 * <h2>Transações</h2>
 * O Axon abre uma transação JTA por {@code ProcessingContext} (ver {@code JtaTransactionManager}); como o
 * command salva dentro dele, o {@code @Transactional} destes métodos <b>entra na mesma transação</b>
 * (propagação {@code REQUIRED}) e commita junto — evento no event store e linha no Postgres, ou nenhum
 * dos dois.
 */
@ApplicationScoped
public class PanachePostRepository implements PostRepository {

    private final EntityManager em;

    PanachePostRepository(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional
    public void save(Post post) {
        em.merge(post);
    }

    /**
     * Ver {@link PostRepository#restore}: escrita nativa, para escapar do filtro de exclusão lógica.
     * Precisa ser <b>nativa</b> porque JPQL passa pelo mapeamento da entidade e o {@code @SQLRestriction}
     * entraria junto — o UPDATE não encontraria a linha que veio trazer de volta.
     */
    @Override
    @Transactional
    public void restore(PostId postId) {
        em.createNativeQuery("update posts set deleted_at = null where id = :id")
                .setParameter("id", postId.value())
                .executeUpdate();
    }

    @Override
    @Transactional
    public Optional<Post> findById(PostId postId) {
        return Optional.ofNullable(em.find(Post.class, postId));
    }

    /**
     * A página, na ordem de criação e com o {@code id} como desempate — {@code createdAt} sozinho não é
     * único, e dois posts criados no mesmo instante fariam a paginação por offset pular ou repetir
     * linhas.
     * <p>
     * O {@code join fetch} do autor é o que o {@code @EntityGraph} fazia na versão Spring: sem ele o
     * Hibernate honraria o {@code EAGER} do {@code Post.author} com um SELECT por autor distinto depois
     * de trazer a página — correto, mas desnecessário quando um join resolve.
     */
    @Override
    @Transactional
    public List<Post> findAll(long offset, int limit) {
        return em.createQuery("select p from Post p join fetch p.author "
                        + "order by p.createdAt asc, p.id asc", Post.class)
                .setFirstResult((int) offset)
                .setMaxResults(limit)
                .getResultList();
    }

    /** Vários posts numa consulta só; o {@code join fetch} do autor evita o SELECT extra por post. */
    @Override
    @Transactional
    public List<Post> findAllById(Collection<PostId> postIds) {
        if (postIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select p from Post p join fetch p.author where p.id in :ids", Post.class)
                .setParameter("ids", postIds)
                .getResultList();
    }

    /**
     * Os posts de vários autores, mais recentes primeiro, numa consulta só — o lote do campo
     * {@code Author.posts}.
     * <p>
     * O agrupamento acontece aqui, e não numa query com {@code group by}: a consulta devolve os posts de
     * todos os autores do lote numa lista só, já ordenada, e o {@code groupingBy} a reparte por autor
     * preservando essa ordem ({@link LinkedHashMap}).
     */
    @Override
    @Transactional
    public Map<UserId, List<Post>> findByAuthorIds(Collection<UserId> authorIds) {
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        return em.createQuery("select p from Post p join fetch p.author a where a.id in :authorIds "
                        + "order by p.createdAt desc, p.id desc", Post.class)
                .setParameter("authorIds", authorIds)
                .getResultList().stream()
                .collect(Collectors.groupingBy(
                        post -> post.author().id(),
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    /**
     * Vários posts já com as tags, numa consulta só — a coleção é {@code LAZY}, então sem o
     * {@code join fetch} o Hibernate faria um SELECT por post e o lote não evitaria nada.
     * <p>
     * O {@code join fetch} devolve uma linha por tag; o Hibernate 6 deduplica os roots sozinho, sem
     * precisar de {@code distinct} (que, se escrito, iria parar no SQL e mudaria o plano à toa).
     */
    @Override
    @Transactional
    public Map<PostId, List<Tag>> findTagsByPostIds(Collection<PostId> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        return em.createQuery("select p from Post p join fetch p.author left join fetch p.tags "
                        + "where p.id in :ids", Post.class)
                .setParameter("ids", postIds)
                .getResultList().stream()
                .collect(Collectors.toMap(Post::id, Post::tags, (first, second) -> first));
    }
}
