package dev.manuelantunes.axonposts.support;

import dev.manuelantunes.axonposts.application.post.PostView;
import dev.manuelantunes.axonposts.application.post.port.PostReadRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read model em memória: o adapter da porta {@link PostReadRepository} usado nos testes de command
 * handler, no lugar do SQLite.
 * <p>
 * Agora que salvar é responsabilidade do command handler, é este duplo que prova que ele salvou — e o
 * que ele salvou.
 */
public final class InMemoryPostReadRepository implements PostReadRepository {

    private final Map<String, PostView> byId = new LinkedHashMap<>();

    @Override
    public void save(PostView view) {
        byId.put(view.id(), view);
    }

    @Override
    public Optional<PostView> findById(String postId) {
        return Optional.ofNullable(byId.get(postId));
    }

    @Override
    public List<PostView> findAll(long offset, int limit) {
        return new ArrayList<>(byId.values()).stream()
                .skip(offset)
                .limit(limit)
                .toList();
    }

    /** Atalho de teste: tudo o que foi salvo, na ordem de inserção. */
    public List<PostView> all() {
        return new ArrayList<>(byId.values());
    }
}
