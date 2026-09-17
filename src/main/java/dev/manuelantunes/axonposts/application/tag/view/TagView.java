package dev.manuelantunes.axonposts.application.tag.view;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

/** DTO de saída de uma tag: o {@code type Tag} do schema. */
@Name("Tag")
@Description("Uma tag. Agregado próprio: tem id e stream de eventos independentes do post")
public record TagView(@Id @NonNull String id, @NonNull String name) {
}
