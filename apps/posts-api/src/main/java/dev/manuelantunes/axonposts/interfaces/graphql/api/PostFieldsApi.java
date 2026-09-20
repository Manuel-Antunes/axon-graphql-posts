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

import dev.manuelantunes.axonposts.application.post.query.FindTagsByPostIdsQuery.FindTagsByPostIds;
import dev.manuelantunes.axonposts.application.post.query.FindTagsByPostIdsQuery.TagsByPost;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.application.tag.view.TagView;
import dev.manuelantunes.axonposts.application.user.query.FindUsersByIdsQuery.FindUsersByIds;
import dev.manuelantunes.axonposts.application.user.query.FindUsersByIdsQuery.UsersById;
import dev.manuelantunes.axonposts.application.user.view.AuthorView;
import dev.manuelantunes.axonposts.application.user.view.UserView;
import dev.manuelantunes.axonposts.interfaces.graphql.error.NotFoundException;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.ConnectionArgs;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.Connections;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.TagConnection;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.TagEdge;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;

@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class PostFieldsApi {
    private static final Logger log = Logger.getLogger(PostFieldsApi.class);

    static final String TAG_CURSOR_TYPE = "tag";

    private final QueryGateway queryGateway;

    public PostFieldsApi(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    @Name("author")
    @NonNull
    @Description("Who wrote it. Resolved in batch: N posts from M authors cost ONE query")
    public Uni<List<AuthorView>> author(@Source List<PostView> posts) {
        List<String> authorIds = posts.stream().map(PostView::authorId).distinct().toList();
        log.debugf("lote de autores: %d numa consulta", authorIds.size());

        return Uni.createFrom()
                .completionStage(() -> queryGateway.query(new FindUsersByIds(authorIds), UsersById.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(UsersById::byId)
                .map(byId -> posts.stream().map(post -> author(byId, post)).toList());
    }

    @Name("tags")
    @NonNull
    @Description("The post's tags, as a Relay cursor connection. Resolved in batch")
    public Uni<List<TagConnection>> tags(
            @Source List<PostView> posts,
            @Name("first") @Description("How many tags to fetch; absent = 20") Integer first,
            @Name("after") @Description("Cursor of the last tag already seen; absent = from the start") String after) {
        ConnectionArgs args = ConnectionArgs.of(TAG_CURSOR_TYPE, first, after);
        List<String> postIds = posts.stream().map(PostView::id).toList();
        log.debugf("lote de tags: %d post(s) numa consulta", postIds.size());

        return Uni.createFrom()
                .completionStage(() -> queryGateway.query(new FindTagsByPostIds(postIds), TagsByPost.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(TagsByPost::byPostId)
                .map(byPostId -> posts.stream().map(post -> connection(byPostId, post, args)).toList());
    }

    private static TagConnection connection(Map<String, List<TagView>> byPostId,
                                            PostView post,
                                            ConnectionArgs args) {
        List<TagView> tags = byPostId.getOrDefault(post.id(), List.of());
        return Connections.slice(tags, args, TagEdge::new, TagConnection::new);
    }

    private static AuthorView author(Map<String, UserView> byId, PostView post) {
        UserView user = byId.get(post.authorId());
        if (user instanceof AuthorView author) {
            return author;
        }
        throw new NotFoundException("o autor do post " + post.id() + " não está mais disponível");
    }
}
