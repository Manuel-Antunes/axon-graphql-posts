package dev.manuelantunes.axonposts.interfaces.graphql.api;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import dev.manuelantunes.axonposts.application.post.subscription.OnPostCreatedSubscription;
import dev.manuelantunes.axonposts.application.post.subscription.OnPostUpdatedSubscription;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import io.smallrye.graphql.api.Subscription;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class PostSubscriptionApi {
    private final OnPostCreatedSubscription onPostCreated;
    private final OnPostUpdatedSubscription onPostUpdated;

    public PostSubscriptionApi(OnPostCreatedSubscription onPostCreated,
                               OnPostUpdatedSubscription onPostUpdated) {
        this.onPostCreated = onPostCreated;
        this.onPostUpdated = onPostUpdated;
    }

    @Subscription("onPostCreated")
    @NonNull
    @Description("Emits on every PostCreated. authorId filters by topic (null = all)")
    public Multi<PostView> onPostCreated(@Name("authorId") @Id String authorId) {
        return onPostCreated.subscribe(authorId);
    }

    @Subscription("onPostUpdated")
    @NonNull
    @Description("Emits on every PostUpdated. The two filters combine (AND); null in both = everything")
    public Multi<PostView> onPostUpdated(@Name("postId") @Id String postId,
                                         @Name("authorId") @Id String authorId) {
        return onPostUpdated.subscribe(postId, authorId);
    }
}
