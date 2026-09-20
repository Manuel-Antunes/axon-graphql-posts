package dev.manuelantunes.axonposts.infrastructure.persistence.post;

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

    @Override
    @Transactional
    public List<Post> findAll(long offset, int limit) {
        return em.createQuery("select p from Post p join fetch p.author "
                        + "order by p.createdAt asc, p.id asc", Post.class)
                .setFirstResult((int) offset)
                .setMaxResults(limit)
                .getResultList();
    }

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
