package dev.manuelantunes.axonposts.infrastructure.persistence.panache;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/** Adapter: implementa a porta {@link TagRepository} com Hibernate ORM + Panache sobre PostgreSQL. */
@ApplicationScoped
public class PanacheTagRepository implements TagRepository {

    private final TagPanache tags;

    PanacheTagRepository(TagPanache tags) {
        this.tags = tags;
    }

    /** {@code merge} pelo mesmo motivo do {@link PanachePostRepository}: a Tag vem do stream, detached. */
    @Override
    @Transactional
    public void save(Tag tag) {
        tags.getEntityManager().merge(tag);
    }

    /**
     * Roda dentro da transação que o Axon abriu para o command, então a Tag devolvida fica
     * <b>gerenciada</b> — é a mesma instância que o {@code merge} do Post vai encontrar no contexto de
     * persistência ao gravar a linha de {@code post_tags}.
     */
    @Override
    @Transactional
    public Optional<Tag> findById(TagId tagId) {
        return tags.findByIdOptional(tagId);
    }

    @Override
    @Transactional
    public List<Tag> findAllById(Collection<TagId> tagIds) {
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return tags.byIds(tagIds);
    }

    @Override
    @Transactional
    public Optional<Tag> findByName(TagName name) {
        return tags.byName(name.value());
    }

    @Override
    @Transactional
    public boolean isEmpty() {
        return tags.count() == 0;
    }
}
