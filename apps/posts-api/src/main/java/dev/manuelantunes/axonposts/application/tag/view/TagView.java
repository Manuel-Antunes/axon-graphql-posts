package dev.manuelantunes.axonposts.application.tag.view;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import io.smallrye.graphql.api.federation.FieldSet;
import io.smallrye.graphql.api.federation.Key;

/**
 * DTO de saída de uma tag: o {@code type Tag} do schema.
 *
 * <h2>{@code @Key}: ter id não era ser entidade</h2>
 * Dentro deste schema a tag só é alcançada a partir de um post. A chave a torna alcançável <b>sozinha</b>
 * por um subgraph vizinho, e quem a resolve é o {@code TagEntityApi} — que existe por causa dela.
 */
@Name("Tag")
@Key(fields = @FieldSet("id"))
@Description("Uma tag. Agregado próprio: tem id e stream de eventos independentes do post")
public record TagView(@Id @NonNull String id, @NonNull String name) {
}
