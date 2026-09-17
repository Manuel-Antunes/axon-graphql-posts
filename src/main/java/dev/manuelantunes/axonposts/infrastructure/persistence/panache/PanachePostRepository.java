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
import jakarta.transaction.Transactional;

/**
 * Adapter: implementa a porta {@link PostRepository} com Hibernate ORM + Panache sobre PostgreSQL.
 * <p>
 * Ficou fino porque não há tradução a fazer — {@link Post} é a entidade JPA. O que sobra é a fronteira
 * transacional e o agrupamento dos resultados em lote.
 *
 * <h2>Por que {@code merge} e não {@code persist}</h2>
 * A entidade que chega aqui vem <b>reconstituída dos eventos pelo Axon</b>, não de uma sessão do
 * Hibernate: ela é sempre <i>detached</i>, exista a linha ou não. O {@code persist()} do Panache falha
 * nesse caso ("detached entity passed to persist"), e é por isso que {@link #save} vai direto ao
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

    private final PostPanache posts;

    PanachePostRepository(PostPanache posts) {
        this.posts = posts;
    }

    @Override
    @Transactional
    public void save(Post post) {
        posts.getEntityManager().merge(post);
    }

    /** Ver {@link PostRepository#restore}: escrita nativa, para escapar do filtro de exclusão lógica. */
    @Override
    @Transactional
    public void restore(PostId postId) {
        posts.undelete(postId);
    }

    @Override
    @Transactional
    public Optional<Post> findById(PostId postId) {
        return posts.findByIdOptional(postId);
    }

    @Override
    @Transactional
    public List<Post> findAll(long offset, int limit) {
        return posts.page(offset, limit);
    }

    /**
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
        return posts.byAuthorIds(authorIds).stream()
                .collect(Collectors.groupingBy(
                        post -> post.author().id(),
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    @Override
    @Transactional
    public Map<PostId, List<Tag>> findTagsByPostIds(Collection<PostId> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        return posts.withTagsByIds(postIds).stream()
                .collect(Collectors.toMap(Post::id, Post::tags, (first, second) -> first));
    }
}
