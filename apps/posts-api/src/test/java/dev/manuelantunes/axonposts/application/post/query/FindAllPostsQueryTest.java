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
import dev.manuelantunes.axonposts.support.UserFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A paginação do {@link FindAllPostsQuery}: ele pede uma linha a mais do que o cliente quer e usa
 * a existência dela como resposta para "tem próxima página?". Estes testes travam as duas pontas —
 * a linha extra nunca vaza para o resultado, e {@code hasNext} bate com a realidade.
 *
 * <h2>O mapper aqui é um DUBLO, e isso é deliberado</h2>
 * Este teste mede paginação: quantos itens a página traz e se {@code hasNext} bate com a realidade. O
 * mapeamento é incidental — só o {@code id} é observado.
 * <p>
 * Ele já dependeu do mapper de verdade por dois caminhos, e os dois custaram estabilidade de BUILD.
 * {@code new PostViewMapperImpl()} nomeia uma classe GERADA a partir de um teste do mesmo módulo;
 * {@code @QuarkusTest} + {@code @Inject PostViewMapper} faz a descoberta da suíte inteira depender de o
 * impl gerado ter saído anotado como bean. Nos dois casos, quando a geração oscilava entre os rounds de
 * compilação, o sintoma não falava de geração:
 * <pre>
 * incompatible types: PostViewMapperImpl cannot be converted to PostViewMapper
 * Could not load class with name: ...FindAllPostsQueryTest
 * </pre>
 * — este último derrubando os 149 testes, não só este.
 * <p>
 * Com o dublo, o teste volta a ser o que era: sem container, sem banco, sem bus, e sem opinião sobre
 * como o mapper é construído. Quem exercita o mapper de verdade é a suíte ponta a ponta, que lê os
 * campos pela borda GraphQL.
 */
class FindAllPostsQueryTest {

    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");

    /** Só o que este teste observa. Os demais campos não participam de nenhuma asserção daqui. */
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
