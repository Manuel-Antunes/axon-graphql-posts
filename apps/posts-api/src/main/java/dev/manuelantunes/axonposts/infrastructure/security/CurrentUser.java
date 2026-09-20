package dev.manuelantunes.axonposts.infrastructure.security;

import java.util.Optional;

import org.eclipse.microprofile.jwt.JsonWebToken;

import dev.manuelantunes.axonposts.application.auth.AuthenticatedUser;
import dev.manuelantunes.axonposts.application.auth.Identity;
import dev.manuelantunes.axonposts.application.auth.UserProvisioning;
import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.Role;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.exception.NotAnAuthorException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CurrentUser implements AuthenticatedUser {
    static final String IDENTITY_PROVIDER = "identity_provider";

    private final UserProvisioning provisioning;
    private final CurrentIdentityAssociation identityAssociation;

    public CurrentUser(UserProvisioning provisioning, CurrentIdentityAssociation identityAssociation) {
        this.provisioning = provisioning;
        this.identityAssociation = identityAssociation;
    }

    @Override
    public Uni<User> require() {
        return identityAssociation.getDeferredIdentity()
                .onItem().transform(CurrentUser::identityOf)
                .onItem().transformToUni(identity -> Uni.createFrom()
                        .item(() -> provisioning.provision(identity))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool()));
    }

    @Override
    public Uni<Author> requireAuthor() {
        return require().onItem().transform(user -> {
            if (user instanceof Author author) {
                return author;
            }
            throw new NotAnAuthorException(user.id());
        });
    }

    private static Identity identityOf(SecurityIdentity securityIdentity) {
        if (securityIdentity == null || securityIdentity.isAnonymous()
                || !(securityIdentity.getPrincipal() instanceof JsonWebToken token)) {
            throw new UnauthorizedException(
                    "requisição sem token: mande Authorization: Bearer <token do Keycloak>");
        }

        String email = claim(token, "email");
        String preferredUsername = claim(token, "preferred_username");
        String name = claim(token, "name");

        return new Identity(
                AuthProvider.fromAlias(claim(token, IDENTITY_PROVIDER)),
                token.getSubject(),
                email != null ? email : preferredUsername,
                name != null && !name.isBlank() ? name : preferredUsername,
                securityIdentity.hasRole(Role.AUTHOR.claim()));
    }

    private static String claim(JsonWebToken token, String name) {
        return Optional.<Object>ofNullable(token.getClaim(name)).map(Object::toString).orElse(null);
    }
}
