package dev.manuelantunes.axonposts.dto.controller;

import java.time.Instant;
import java.util.List;

/**
 * DTO de <b>saída</b> do GraphQL: o {@code type Post} do schema, e nada além disso.
 * <p>
 * Deixou de ser um read model. O estado gravado é a própria entidade de domínio {@code Post} — este
 * record existe só para achatar os value objects em primitivos na borda, para que o schema não exponha
 * {@code {"title": {"value": "..."}}} e para que uma mudança no domínio não vire, sem querer, uma
 * mudança de contrato com o cliente.
 * <p>
 * É o mesmo shape em {@code post}, {@code posts}, {@code onPostCreated} e {@code onPostUpdated}.
 *
 * @param version quantidade de eventos aplicados a esse Post (1 = só criado)
 */
public record PostView(
        String id,
        String title,
        String content,
        String author,
        Instant createdAt,
        Instant updatedAt,
        long version,
        List<TagView> tags
) {
}
