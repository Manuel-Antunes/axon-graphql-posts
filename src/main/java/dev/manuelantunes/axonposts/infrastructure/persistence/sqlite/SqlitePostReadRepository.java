package dev.manuelantunes.axonposts.infrastructure.persistence.sqlite;

import dev.manuelantunes.axonposts.application.post.PostView;
import dev.manuelantunes.axonposts.application.post.port.PostReadRepository;
import dev.manuelantunes.axonposts.mapper.PostEntityMapper;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Adapter: implementa a porta {@link PostReadRepository} com Spring Data JPA sobre SQLite. É o único
 * lugar do projeto que sabe que o read model é SQLite — e, com o {@link PostEntityMapper}, o único que
 * sabe que existe uma entidade JPA.
 * <p>
 * O Axon 5 abre uma transação Spring ({@code SpringTransactionManager}) por {@code ProcessingContext};
 * como o command handler salva dentro dele, o {@code save} entra nessa mesma transação e commita junto.
 * As leituras das queries abrem a própria transação read-only.
 */
@Repository
public class SqlitePostReadRepository implements PostReadRepository {

    private final SpringDataPostRepository repository;
    private final PostEntityMapper mapper;

    public SqlitePostReadRepository(SpringDataPostRepository repository, PostEntityMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void save(PostView view) {
        repository.save(mapper.toEntity(view));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PostView> findById(String postId) {
        return repository.findById(postId).map(mapper::toView);
    }

    /**
     * {@code offset} é o índice da primeira linha desta página. A conversão para {@link ScrollPosition}
     * segue a convenção do Spring Data: {@code initial()} começa em 0 e {@code of(n)} começa em
     * {@code n + 1} — daí o {@code offset - 1}.
     */
    @Override
    @Transactional(readOnly = true)
    public List<PostView> findAll(long offset, int limit) {
        ScrollPosition position = offset <= 0
                ? ScrollPosition.offset()
                : ScrollPosition.offset(offset - 1);

        return repository.findAllByOrderByCreatedAtAscIdAsc(position, Limit.of(limit))
                .stream()
                .map(mapper::toView)
                .toList();
    }
}
