package dev.manuelantunes.axonposts.application.user.view;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import io.smallrye.graphql.api.federation.FieldSet;
import io.smallrye.graphql.api.federation.Key;

@Name("Author")
@Key(fields = @FieldSet("id"))
@Description("A user who writes. A table-per-type subclass of User: a row in `users` + a row in `authors`")
public record AuthorView(
        @Id @NonNull String id,
        @NonNull String name,
        @NonNull String email,
        @NonNull @Description("A field only the subclass has, which is why it can be NOT NULL in `authors`")
        String bio,
        @NonNull List<AccountView> accounts
) implements UserView {
}
