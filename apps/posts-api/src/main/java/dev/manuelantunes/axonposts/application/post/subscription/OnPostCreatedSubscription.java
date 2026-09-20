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
public class OnPostCreatedSubscription {
    @Query(namespace = "posts", name = "OnPostCreated", version = "1.0.0")
    public record OnPostCreated(String authorId) {
        public boolean matches(String createdByAuthorId) {
            return authorId == null || authorId.isBlank() || authorId.equals(createdByAuthorId);
        }
    }

    private static final int UPDATE_BUFFER = 256;

    private final QueryGateway queryGateway;

    public OnPostCreatedSubscription(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    @QueryHandler
    public Optional<PostView> initialResult(OnPostCreated subscription) {
        return Optional.empty();
    }

    public Multi<PostView> subscribe(String authorId) {
        return Multi.createFrom()
                .publisher(FlowAdapters.toFlowPublisher(
                        queryGateway.subscriptionQuery(new OnPostCreated(authorId), PostView.class)))
                .onOverflow().buffer(UPDATE_BUFFER);
    }
}
