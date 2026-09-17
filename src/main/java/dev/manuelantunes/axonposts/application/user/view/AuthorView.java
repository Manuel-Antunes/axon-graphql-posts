package dev.manuelantunes.axonposts.application.user.view;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * DTO de saída de um autor: o {@code type Author} do schema.
 * <p>
 * {@code bio} vem junto porque o Hibernate já a trouxe: carregar um {@code Author} numa herança
 * {@code JOINED} é um join com {@code authors}, e a coluna vem na mesma linha. Resolver esse campo à
 * parte era pagar uma consulta por um dado que já estava em memória.
 * <p>
 * {@code posts} continua sendo campo resolvido à parte — é uma coleção aberta e paginada, e aí a consulta
 * separada é o desenho certo, não desperdício. Ver {@code AuthorFieldsApi}.
 */
@Name("Author")
@Description("Usuário que escreve. Subclasse table-per-type de User: linha em `users` + linha em `authors`")
public record AuthorView(
        @Id @NonNull String id,
        @NonNull String name,
        @NonNull String email,
        @NonNull @Description("Campo que só a subclasse tem, e por isso pode ser NOT NULL em `authors`")
        String bio,
        @NonNull List<AccountView> accounts
) implements UserView {
}
