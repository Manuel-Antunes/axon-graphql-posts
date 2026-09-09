package dev.manuelantunes.axonposts.infrastructure.persistence.jpa;

import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Adapter: implementa a porta {@link TagRepository} com Spring Data JPA sobre PostgreSQL. */
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

    /**
     * Roda dentro da transação que o Axon abriu para o command, então a Tag devolvida fica
     * <b>gerenciada</b> — é a mesma instância que o {@code merge} do Post vai encontrar no contexto de
     * persistência ao gravar a linha de {@code post_tags}.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Tag> findById(TagId tagId) {
        return repository.findById(tagId);
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
