package dev.manuelantunes.axonposts.application.post.view;

import java.time.Instant;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Ignore;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import io.smallrye.graphql.api.federation.FieldSet;
import io.smallrye.graphql.api.federation.Key;

@Name("Post")
@Key(fields = @FieldSet("id"))
@Description("A published post, with the state resulting from every event applied to it")
public record PostView(
        @Id @NonNull String id,
        @NonNull String title,
        @NonNull String content,
        @Ignore String authorId,
        @NonNull Instant createdAt,
        @NonNull Instant updatedAt,
        int version
) {
}
