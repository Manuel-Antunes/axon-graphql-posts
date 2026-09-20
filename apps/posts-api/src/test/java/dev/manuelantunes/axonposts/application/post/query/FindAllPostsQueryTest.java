package dev.manuelantunes.axonposts.application.post.query;

import dev.manuelantunes.axonposts.application.post.query.FindAllPostsQuery.FindAllPosts;
import dev.manuelantunes.axonposts.application.post.view.PostPage;
import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.event.PostPreCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.application.post.view.PostViewMapper;
import dev.manuelantunes.axonposts.application.tag.view.TagView;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.support.InMemoryPostRepository;
import dev.manuelantunes.axonposts.testing.UserFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FindAllPostsQueryTest {
    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");

    private final PostViewMapper viewMapper = new PostViewMapper() {
        @Override
        public PostView toView(Post post) {
            return new PostView(post.id().value(), post.title().value(), post.content().value(),
                    post.author().id().value(), post.createdAt(), post.updatedAt(),
                    (int) post.version().value());
        }

        @Override
        public TagView toTagView(Tag tag) {
            return new TagView(tag.id().value(), tag.name().value());
        }

        @Override
        public List<TagView> toTagViews(List<Tag> tags) {
            return tags.stream().map(this::toTagView).toList();
        }
    };

    private InMemoryPostRepository posts;
    private FindAllPostsQuery query;

    @BeforeEach
    void setUp() {
        posts = new InMemoryPostRepository();
        query = new FindAllPostsQuery(posts, viewMapper);
        for (int i = 0; i < 5; i++) {
            posts.save(new Post(new PostPreCreatedEvent(
                    PostId.of("id-" + i), "título " + i, "conteúdo",
                    UserFixtures.AUTHOR_ID, T0)));
        }
    }

    @Test
    void returnsExactlyTheRequestedPageAndFlagsThatThereIsMore() {
        PostPage page = query.handle(new FindAllPosts(0, 2));

        assertThat(page.items()).extracting(PostView::id).containsExactly("id-0", "id-1");
        assertThat(page.offset()).isZero();
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void theLastFullPageKnowsThereIsNothingAfterIt() {
        PostPage page = query.handle(new FindAllPosts(3, 2));

        assertThat(page.items()).extracting(PostView::id).containsExactly("id-3", "id-4");
        assertThat(page.offset()).isEqualTo(3);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void aPartialPageHasNoNext() {
        PostPage page = query.handle(new FindAllPosts(4, 10));

        assertThat(page.items()).extracting(PostView::id).containsExactly("id-4");
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void anOffsetPastTheEndIsAnEmptyPage() {
        PostPage page = query.handle(new FindAllPosts(99, 10));

        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
    }
}
