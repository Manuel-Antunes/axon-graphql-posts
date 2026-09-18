package dev.manuelantunes.axonposts.infrastructure.persistence.panache;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Adapter: implementa a porta {@link TagRepository} com Hibernate ORM sobre PostgreSQL.
 * Ver {@link PanachePostRepository} sobre por que o {@code EntityManager} vem pelo construtor.
 */
@ApplicationScoped
public class PanacheTagRepository implements TagRepository {

    private final EntityManager em;

    PanacheTagRepository(EntityManager em) {
        this.em = em;
    }

    /** {@code merge} pelo mesmo motivo do {@link PanachePostRepository}: a Tag vem do stream, detached. */
    @Override
    @Transactional
    public void save(Tag tag) {
        em.merge(tag);
    }

    /**
     * Roda dentro da transação que o Axon abriu para o command, então a Tag devolvida fica
     * <b>gerenciada</b> — é a mesma instância que o {@code merge} do Post vai encontrar no contexto de
     * persistência ao gravar a linha de {@code post_tags}.
     */
    @Override
    @Transactional
    public Optional<Tag> findById(TagId tagId) {
        return Optional.ofNullable(em.find(Tag.class, tagId));
    }

    @Override
    @Transactional
    public List<Tag> findAllById(Collection<TagId> tagIds) {
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select t from Tag t where t.id in :ids", Tag.class)
                .setParameter("ids", tagIds)
                .getResultList();
    }

    /**
     * O {@code name} é um {@code @Embedded TagName}, então a consulta navega até o campo do embeddable.
     * Sem diferenciar caixa, pelo mesmo motivo que {@code TagName.sameAs} não diferencia: "Untagged" e
     * "untagged" são a mesma tag.
     */
    @Override
    @Transactional
    public Optional<Tag> findByName(TagName name) {
        return em.createQuery("select t from Tag t where lower(t.name.value) = lower(:name)", Tag.class)
                .setParameter("name", name.value())
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    @Override
    @Transactional
    public boolean isEmpty() {
        return em.createQuery("select count(t) from Tag t", Long.class).getSingleResult() == 0;
    }
}
