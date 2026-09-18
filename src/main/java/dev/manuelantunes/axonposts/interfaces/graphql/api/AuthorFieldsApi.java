package dev.manuelantunes.axonposts.interfaces.graphql.api;

import java.util.List;
import java.util.Map;

import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Source;
import org.jboss.logging.Logger;

import dev.manuelantunes.axonposts.application.post.query.FindPostsByAuthorIdsQuery.FindPostsByAuthorIds;
import dev.manuelantunes.axonposts.application.post.query.FindPostsByAuthorIdsQuery.PostsByAuthor;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.application.user.view.AuthorView;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.ConnectionArgs;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.Connections;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.PostConnection;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.PostEdge;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * O campo {@code Author.posts}: cursor connection servida em lote, gêmea de {@code Post.tags}.
 *
 * <h2>O N+1 que isto resolve</h2>
 * {@code posts(first: 20) { edges { node { author { posts { … } } } } }} pediria os posts de 20 autores.
 * Com o lote, os ids distintos vão numa consulta só.
 *
 * <h2>Recorte em memória: aqui o limite é mais perto do que nas tags</h2>
 * As tags de um post são poucas por natureza. Os posts de um autor <b>não</b> são: um autor produtivo
 * acumula milhares, e trazer todos para devolver os 20 primeiros é desperdício que cresce com o tempo.
 * <p>
 * A troca é consciente e vale enquanto o volume for de POC. O caminho de saída não muda a fronteira
 * deste método: seria uma consulta com {@code row_number() over (partition by author_id order by
 * created_at desc)} dentro da própria consulta em lote, devolvendo já recortado.
 */
@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class AuthorFieldsApi {

    private static final Logger log = Logger.getLogger(AuthorFieldsApi.class);

    private final QueryGateway queryGateway;

    public AuthorFieldsApi(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    /** Autor sem post nenhum não vem no mapa do lote — daí o {@code null} virar lista vazia. */
    @Name("posts")
    @NonNull
    @Description("Posts deste autor, mais recentes primeiro, como Relay cursor connection")
    public Uni<List<PostConnection>> posts(
            @Source List<AuthorView> authors,
            @Name("first") @Description("Quantos posts trazer; ausente = 20") Integer first,
            @Name("after") @Description("Cursor do último post já visto; ausente = do começo") String after) {

        ConnectionArgs args = ConnectionArgs.of(PostQueryApi.CURSOR_TYPE, first, after);
        List<String> authorIds = authors.stream().map(AuthorView::id).distinct().toList();
        log.debugf("lote de posts por autor: %d autor(es) numa consulta", authorIds.size());

        return Uni.createFrom()
                .completionStage(() ->
                        queryGateway.query(new FindPostsByAuthorIds(authorIds), PostsByAuthor.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(PostsByAuthor::byAuthorId)
                .map(byAuthorId -> authors.stream()
                        .map(author -> connection(byAuthorId, author, args))
                        .toList());
    }

    private static PostConnection connection(Map<String, List<PostView>> byAuthorId,
                                             AuthorView author,
                                             ConnectionArgs args) {
        List<PostView> posts = byAuthorId.getOrDefault(author.id(), List.of());
        return Connections.slice(posts, args, PostEdge::new, PostConnection::new);
    }
}
