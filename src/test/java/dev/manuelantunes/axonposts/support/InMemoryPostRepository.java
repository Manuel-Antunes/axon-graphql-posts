package dev.manuelantunes.axonposts.support;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Repositório de Posts em memória: o adapter da porta {@link PostRepository} usado nos testes, no lugar
 * do SQLite. Como salvar é responsabilidade do command, é este duplo que prova que ele salvou —
 * e o que ele salvou.
 */
public final class InMemoryPostRepository implements PostRepository {

    private final Map<PostId, Post> byId = new LinkedHashMap<>();

    @Override
    public void save(Post post) {
        byId.put(post.id(), post);
    }

    @Override
    public Optional<Post> findById(PostId postId) {
        return Optional.ofNullable(byId.get(postId));
    }

    @Override
    public List<Post> findAll(long offset, int limit) {
        return byId.values().stream().skip(offset).limit(limit).toList();
    }

    @Override
    public Map<PostId, List<Tag>> findTagsByPostIds(Collection<PostId> postIds) {
        return postIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .filter(post -> !post.tags().isEmpty())
                .collect(Collectors.toMap(Post::id, Post::tags));
    }

    /** Atalho de teste: tudo o que foi salvo, na ordem de inserção. */
    public List<Post> all() {
        return new ArrayList<>(byId.values());
    }
}
