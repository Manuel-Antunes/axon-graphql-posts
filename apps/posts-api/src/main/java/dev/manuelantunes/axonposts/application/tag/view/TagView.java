package dev.manuelantunes.axonposts.application.tag.view;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import io.smallrye.graphql.api.federation.FieldSet;
import io.smallrye.graphql.api.federation.Key;

@Name("Tag")
@Key(fields = @FieldSet("id"))
@Description("A tag. An aggregate of its own: it has an id and an event stream independent of the post")
public record TagView(@Id @NonNull String id, @NonNull String name) {
}
