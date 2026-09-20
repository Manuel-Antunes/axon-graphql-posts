package dev.manuelantunes.axonposts.interfaces.graphql.api;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import dev.manuelantunes.axonposts.application.auth.AuthenticatedUser;
import dev.manuelantunes.axonposts.application.post.command.DeletePostCommand.DeletePost;
import dev.manuelantunes.axonposts.application.post.command.RestorePostCommand.RestorePost;
import dev.manuelantunes.axonposts.application.post.query.FindPostQuery.FindPost;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.user.Role;
import dev.manuelantunes.axonposts.interfaces.graphql.dto.CreatePostInput;
import dev.manuelantunes.axonposts.interfaces.graphql.dto.UpdatePostInput;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import dev.manuelantunes.axonposts.interfaces.graphql.mapper.PostInputMapper;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;

@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class PostMutationApi {
    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final PostInputMapper inputMapper;
    private final AuthenticatedUser currentUser;

    public PostMutationApi(CommandGateway commandGateway,
                           QueryGateway queryGateway,
                           PostInputMapper inputMapper,
                           AuthenticatedUser currentUser) {
        this.commandGateway = commandGateway;
        this.queryGateway = queryGateway;
        this.inputMapper = inputMapper;
        this.currentUser = currentUser;
    }

    @Mutation("createPost")
    @NonNull
    @RolesAllowed(Role.AUTHOR_CLAIM)
    @Description("Dispatches the CreatePost command and returns the already-projected Post")
    public Uni<PostView> createPost(@Name("input") @NonNull @Valid CreatePostInput input) {
        return currentUser.requireAuthor()
                .map(author -> inputMapper.toCommand(PostId.newId(), input, author.id()))
                .flatMap(command -> Uni.createFrom()
                        .completionStage(() -> commandGateway.send(command, PostId.class))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                        .flatMap(this::savedPost));
    }

    @Mutation("updatePost")
    @NonNull
    @RolesAllowed(Role.AUTHOR_CLAIM)
    @Description("Dispatches the UpdatePost command and returns the already-projected Post")
    public Uni<PostView> updatePost(@Name("input") @NonNull @Valid UpdatePostInput input) {
        return currentUser.requireAuthor()
                .map(author -> inputMapper.toCommand(input, author.id()))
                .flatMap(command -> Uni.createFrom()
                        .completionStage(() -> commandGateway.send(command, Void.class))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                        .flatMap(ignored -> savedPost(command.postId())));
    }

    @Mutation("deletePost")
    @NonNull
    @RolesAllowed(Role.AUTHOR_CLAIM)
    @Description("Logical deletion: the post disappears from queries, but the row and the stream stay")
    public Uni<Boolean> deletePost(@Name("id") @Id @NonNull String id) {
        return currentUser.requireAuthor()
                .flatMap(author -> Uni.createFrom()
                        .completionStage(() -> commandGateway.send(
                                new DeletePost(PostId.of(id), author.id()), Void.class))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
                .replaceWith(Boolean.TRUE);
    }

    @Mutation("restorePost")
    @NonNull
    @RolesAllowed(Role.AUTHOR_CLAIM)
    @Description("Undoes the logical deletion. It works because the aggregate is rehydrated from the events")
    public Uni<PostView> restorePost(@Name("id") @Id @NonNull String id) {
        PostId postId = PostId.of(id);
        return currentUser.requireAuthor()
                .flatMap(author -> Uni.createFrom()
                        .completionStage(() -> commandGateway.send(
                                new RestorePost(postId, author.id()), Void.class))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
                .flatMap(ignored -> savedPost(postId));
    }

    private Uni<PostView> savedPost(PostId postId) {
        return Uni.createFrom()
                .completionStage(() -> queryGateway.query(new FindPost(postId.value()), PostView.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
