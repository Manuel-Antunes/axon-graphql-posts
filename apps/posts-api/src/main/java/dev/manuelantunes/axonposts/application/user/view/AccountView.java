package dev.manuelantunes.axonposts.application.user.view;

import java.time.Instant;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import dev.manuelantunes.axonposts.domain.user.AuthProvider;

@Name("Account")
@Description("A credential. Identity in `users`, credential in `accounts`, one row per provider")
public record AccountView(
        @NonNull AuthProvider provider,
        @NonNull @Description("The account's identifier AT the provider (the token's `sub`). Not the e-mail")
        String subject,
        @Description("Whether this account can sign in with a password. False for every federated account")
        boolean hasPassword,
        @NonNull Instant linkedAt
) {
}
