package dev.manuelantunes.axonposts.infrastructure.persistence.sqlite;

import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Adapter: implementa a porta {@link TagRepository} com Spring Data JPA sobre SQLite. */
@Repository
public class JpaTagRepository implements TagRepository {

    private final SpringDataTagRepository repository;

    public JpaTagRepository(SpringDataTagRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(Tag tag) {
        repository.save(tag);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tag> findByName(TagName name) {
        return repository.findByNameValueIgnoreCase(name.value());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return repository.count() == 0;
    }
}
