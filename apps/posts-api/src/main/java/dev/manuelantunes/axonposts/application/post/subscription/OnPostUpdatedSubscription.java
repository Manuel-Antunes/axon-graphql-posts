package dev.manuelantunes.axonposts.application.post.subscription;

import java.util.Optional;

import org.axonframework.messaging.queryhandling.annotation.Query;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.reactivestreams.FlowAdapters;

import dev.manuelantunes.axonposts.application.post.view.PostView;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OnPostUpdatedSubscription {
    @Query(namespace = "posts", name = "OnPostUpdated", version = "1.0.0")
    public record OnPostUpdated(String postId, String authorId) {
        public boolean matches(String updatedPostId, String updatedAuthorId) {
            return matchesTopic(postId, updatedPostId) && matchesTopic(authorId, updatedAuthorId);
        }

        private static boolean matchesTopic(String filter, String actual) {
            return filter == null || filter.isBlank() || filter.equals(actual);
        }
    }

    private static final int UPDATE_BUFFER = 256;

    private final QueryGateway queryGateway;

    public OnPostUpdatedSubscription(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    @QueryHandler
    public Optional<PostView> initialResult(OnPostUpdated subscription) {
        return Optional.empty();
    }

    public Multi<PostView> subscribe(String postId, String authorId) {
        return Multi.createFrom()
                .publisher(FlowAdapters.toFlowPublisher(
                        queryGateway.subscriptionQuery(new OnPostUpdated(postId, authorId), PostView.class)))
                .onOverflow().buffer(UPDATE_BUFFER);
    }
}
