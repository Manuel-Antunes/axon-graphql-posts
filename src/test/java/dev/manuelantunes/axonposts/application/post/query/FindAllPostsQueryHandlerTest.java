package dev.manuelantunes.axonposts.application.post.query;

import dev.manuelantunes.axonposts.application.post.PostPage;
import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.dto.controller.PostView;
import dev.manuelantunes.axonposts.mapper.PostViewMapperImpl;
import dev.manuelantunes.axonposts.support.InMemoryPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A paginação do {@link FindAllPostsQueryHandler}: ele pede uma linha a mais do que o cliente quer e usa
 * a existência dela como resposta para "tem próxima página?". Estes testes travam as duas pontas —
 * a linha extra nunca vaza para o resultado, e {@code hasNext} bate com a realidade.
 */
class FindAllPostsQueryHandlerTest {

    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");

    private InMemoryPostRepository posts;
    private FindAllPostsQueryHandler handler;

    @BeforeEach
    void setUp() {
        posts = new InMemoryPostRepository();
        handler = new FindAllPostsQueryHandler(posts, new PostViewMapperImpl());
        for (int i = 0; i < 5; i++) {
            posts.save(new Post(new PostCreatedEvent(
                    PostId.of("id-" + i), "título " + i, "conteúdo", "manuel", T0)));
        }
    }

    @Test
    void returnsExactlyTheRequestedPageAndFlagsThatThereIsMore() {
        PostPage page = handler.handle(new FindAllPostsQuery(0, 2));

        assertThat(page.items()).extracting(PostView::id).containsExactly("id-0", "id-1");
        assertThat(page.offset()).isZero();
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void theLastFullPageKnowsThereIsNothingAfterIt() {
        PostPage page = handler.handle(new FindAllPostsQuery(3, 2));

        assertThat(page.items()).extracting(PostView::id).containsExactly("id-3", "id-4");
        assertThat(page.offset()).isEqualTo(3);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void aPartialPageHasNoNext() {
        PostPage page = handler.handle(new FindAllPostsQuery(4, 10));

        assertThat(page.items()).extracting(PostView::id).containsExactly("id-4");
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void anOffsetPastTheEndIsAnEmptyPage() {
        PostPage page = handler.handle(new FindAllPostsQuery(99, 10));

        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
    }
}
