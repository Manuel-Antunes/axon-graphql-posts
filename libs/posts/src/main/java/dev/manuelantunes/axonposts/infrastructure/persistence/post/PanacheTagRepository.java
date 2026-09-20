package dev.manuelantunes.axonposts.infrastructure.persistence.post;

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

@ApplicationScoped
public class PanacheTagRepository implements TagRepository {
    private final EntityManager em;

    PanacheTagRepository(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional
    public void save(Tag tag) {
        em.merge(tag);
    }

    @Override
    @Transactional
    public void saveIfAbsent(Tag tag) {
        em.createNativeQuery("""
                insert into tags (id, name, created_at) values (?1, ?2, ?3)
                on conflict do nothing
                """)
                .setParameter(1, tag.id().value())
                .setParameter(2, tag.name().value())
                .setParameter(3, java.time.OffsetDateTime.ofInstant(tag.createdAt(), java.time.ZoneOffset.UTC))
                .executeUpdate();
    }

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
