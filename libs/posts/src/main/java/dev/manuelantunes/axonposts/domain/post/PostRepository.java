package dev.manuelantunes.axonposts.domain.post;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PostRepository {
    void save(Post post);

    Optional<Post> findById(PostId postId);

    List<Post> findAll(long offset, int limit);

    List<Post> findAllById(Collection<PostId> postIds);

    Map<PostId, List<Tag>> findTagsByPostIds(Collection<PostId> postIds);

    Map<UserId, List<Post>> findByAuthorIds(Collection<UserId> authorIds);

    void restore(PostId postId);
}
