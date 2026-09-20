package dev.manuelantunes.axonposts.interfaces.graphql.api;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.NonNull;

import dev.manuelantunes.axonposts.application.auth.AuthenticatedUser;
import dev.manuelantunes.axonposts.application.user.command.DeleteUserCommand.DeleteUser;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;

@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class MeMutationApi {
    private final CommandGateway commandGateway;
    private final AuthenticatedUser currentUser;

    public MeMutationApi(CommandGateway commandGateway, AuthenticatedUser currentUser) {
        this.commandGateway = commandGateway;
        this.currentUser = currentUser;
    }

    @Mutation("deleteMe")
    @NonNull
    @Authenticated
    @Description("Deletes your own account (logical deletion). Requires only being authenticated")
    public Uni<Boolean> deleteMe() {
        return currentUser.require()
                .flatMap(user -> Uni.createFrom()
                        .completionStage(() -> commandGateway.send(new DeleteUser(user.id()), Void.class))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
                .replaceWith(Boolean.TRUE);
    }
}
