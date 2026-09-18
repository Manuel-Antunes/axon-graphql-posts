package dev.manuelantunes.axonposts.application.post.query;

import dev.manuelantunes.axonposts.application.post.view.PostPage;
import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.application.post.view.PostViewMapper;
import org.axonframework.messaging.queryhandling.annotation.Query;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * A query <b>FindAllPosts</b>: a mensagem {@link FindAllPosts} e como ela é respondida, num arquivo só —
 * mesma convenção dos commands, com a classe levando o nome da query e o record aninhado só o da ação.
 * <p>
 * A mecânica da paginação mora aqui, e não no controller nem no repositório: pede-se <b>uma linha a
 * mais</b> do que o cliente quer, e a existência dessa linha extra é a resposta para "tem próxima
 * página?". Ela é descartada antes de sair — o cliente recebe exatamente o que pediu.
 */
@ApplicationScoped
public class FindAllPostsQuery {

    /**
     * A mensagem: uma página de Posts, em ordem de criação.
     * <p>
     * Paginação em {@code offset}/{@code limit} crus: a query é uma mensagem, e mensagem não carrega
     * tipo de framework. Quem transforma o cursor do GraphQL nestes dois números é o controller.
     *
     * @param offset índice da primeira linha desejada, contando de 0
     * @param limit  quantidade máxima de linhas na página
     */
    @Query(namespace = "posts", name = "FindAllPosts", version = "1.0.0")
    public record FindAllPosts(long offset, int limit) {
    }

    private final PostRepository posts;
    private final PostViewMapper viewMapper;

    public FindAllPostsQuery(PostRepository posts, PostViewMapper viewMapper) {
        this.posts = posts;
        this.viewMapper = viewMapper;
    }

    @QueryHandler
    public PostPage handle(FindAllPosts query) {
        int limit = query.limit();
        List<Post> rows = posts.findAll(query.offset(), limit + 1);

        boolean hasNext = rows.size() > limit;
        List<PostView> items = (hasNext ? rows.subList(0, limit) : rows).stream()
                .map(viewMapper::toView)
                .toList();

        return new PostPage(items, query.offset(), hasNext);
    }
}
