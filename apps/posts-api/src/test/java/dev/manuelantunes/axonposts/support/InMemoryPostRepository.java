package dev.manuelantunes.axonposts.support;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
    public void restore(PostId postId) {
    }

    @Override
    public List<Post> findAll(long offset, int limit) {
        return byId.values().stream().skip(offset).limit(limit).toList();
    }

    @Override
    public List<Post> findAllById(Collection<PostId> postIds) {
        return postIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public Map<PostId, List<Tag>> findTagsByPostIds(Collection<PostId> postIds) {
        return postIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .filter(post -> !post.tags().isEmpty())
                .collect(Collectors.toMap(Post::id, Post::tags));
    }

    @Override
    public Map<UserId, List<Post>> findByAuthorIds(Collection<UserId> authorIds) {
        return byId.values().stream()
                .filter(post -> authorIds.contains(post.author().id()))
                .collect(Collectors.groupingBy(post -> post.author().id(), LinkedHashMap::new, Collectors.toList()));
    }

    public List<Post> all() {
        return new ArrayList<>(byId.values());
    }
}
