package dev.manuelantunes.axonposts.application.user.view;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import io.smallrye.graphql.api.federation.FieldSet;
import io.smallrye.graphql.api.federation.Key;

@Name("Reader")
@Key(fields = @FieldSet("id"))
@Description("A user who only reads. It is Java's `User` with no row in the `authors` table")
public record ReaderView(
        @Id @NonNull String id,
        @NonNull String name,
        @NonNull String email,
        @NonNull List<AccountView> accounts
) implements UserView {
}
