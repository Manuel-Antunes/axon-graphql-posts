package dev.manuelantunes.axonposts.infrastructure.persistence.sqlite;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Adapter: implementa a porta {@link PostRepository} com Spring Data JPA sobre SQLite.
 * <p>
 * Ficou fino porque não há mais tradução a fazer — {@link Post} é a entidade JPA, então o que sobra é a
 * conversão de {@code offset} para {@link ScrollPosition} e a fronteira transacional.
 * <p>
 * O Axon 5 abre uma transação Spring por {@code ProcessingContext}; como o command salva dentro
 * dele, o {@code save} entra nessa mesma transação e commita junto.
 */
@Repository
public class JpaPostRepository implements PostRepository {

    private final SpringDataPostRepository repository;

    public JpaPostRepository(SpringDataPostRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(Post post) {
        repository.save(post);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Post> findById(PostId postId) {
        return repository.findById(postId);
    }

    /**
     * {@code offset} é o índice da primeira linha desta página. A conversão para {@link ScrollPosition}
     * segue a convenção do Spring Data: {@code initial()} começa em 0 e {@code of(n)} começa em
     * {@code n + 1} — daí o {@code offset - 1}.
     */
    @Override
    @Transactional(readOnly = true)
    public List<Post> findAll(long offset, int limit) {
        ScrollPosition position = offset <= 0
                ? ScrollPosition.offset()
                : ScrollPosition.offset(offset - 1);

        return repository.findAllByOrderByCreatedAtAscIdAsc(position, Limit.of(limit))
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<PostId, List<Tag>> findTagsByPostIds(Collection<PostId> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        return repository.findAllWithTagsByIdIn(postIds).stream()
                .collect(Collectors.toMap(Post::id, Post::tags, (first, second) -> first));
    }
}
