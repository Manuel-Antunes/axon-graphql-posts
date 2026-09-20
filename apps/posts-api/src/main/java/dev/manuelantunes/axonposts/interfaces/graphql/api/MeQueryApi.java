package dev.manuelantunes.axonposts.interfaces.graphql.api;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

import dev.manuelantunes.axonposts.application.auth.AuthenticatedUser;
import dev.manuelantunes.axonposts.application.user.view.UserView;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import dev.manuelantunes.axonposts.application.user.view.UserViewMapper;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class MeQueryApi {
    private final AuthenticatedUser currentUser;
    private final UserViewMapper userViewMapper;

    public MeQueryApi(AuthenticatedUser currentUser, UserViewMapper userViewMapper) {
        this.currentUser = currentUser;
        this.userViewMapper = userViewMapper;
    }

    @Query("me")
    @NonNull
    @Authenticated
    @Description("Who is signed in. Requires Authorization: Bearer <token issued by Keycloak>")
    public Uni<UserView> me() {
        return currentUser.require().map(userViewMapper::toView);
    }
}
