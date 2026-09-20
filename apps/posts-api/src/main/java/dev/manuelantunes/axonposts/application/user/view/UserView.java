package dev.manuelantunes.axonposts.application.user.view;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import io.smallrye.graphql.api.federation.FieldSet;
import io.smallrye.graphql.api.federation.Key;

@Name("User")
@Key(fields = @FieldSet("id"))
@Description("Whoever has an account. An interface because the domain hierarchy is polymorphic")
public sealed interface UserView permits ReaderView, AuthorView {
    @Name("id")
    @Id
    @NonNull
    String id();

    @Name("name")
    @NonNull
    String name();

    @Name("email")
    @NonNull
    String email();

    @Name("accounts")
    @NonNull
    @Description("This user's credentials, one per provider")
    List<AccountView> accounts();
}
