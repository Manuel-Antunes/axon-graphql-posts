package dev.manuelantunes.axonposts.dto.controller;

import java.time.Instant;

/**
 * DTO de <b>saída</b> do GraphQL: os campos escalares do {@code type Post}, e nada além disso.
 * <p>
 * Deixou de ser um read model. O estado gravado é a própria entidade de domínio {@code Post} — este
 * record existe só para achatar os value objects em primitivos na borda, para que o schema não exponha
 * {@code {"title": {"value": "..."}}} e para que uma mudança no domínio não vire, sem querer, uma
 * mudança de contrato com o cliente.
 * <p>
 * <b>As tags não estão aqui de propósito.</b> Elas são um campo à parte no schema, resolvido pelo
 * {@code PostTagsController} através de um DataLoader — carregar tudo junto seria exatamente o N+1 que
 * o DataLoader existe para evitar. Quem não pede {@code tags} na query não paga por elas.
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
        long version
) {
}
