package dev.manuelantunes.axonposts.application.user.view;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import io.smallrye.graphql.api.federation.FieldSet;
import io.smallrye.graphql.api.federation.Key;

/**
 * DTO de saída de um autor: o {@code type Author} do schema.
 * <p>
 * {@code bio} vem junto porque o Hibernate já a trouxe: carregar um {@code Author} numa herança
 * {@code JOINED} é um join com {@code authors}, e a coluna vem na mesma linha. Resolver esse campo à
 * parte era pagar uma consulta por um dado que já estava em memória.
 * <p>
 * {@code posts} continua sendo campo resolvido à parte — é uma coleção aberta e paginada, e aí a consulta
 * separada é o desenho certo, não desperdício. Ver {@code AuthorFieldsApi}.
 *
 * <h2>A chave é a MESMA da interface, e isso é obrigação</h2>
 * {@code UserView} leva {@code @Key(fields = "id")} — é interface de entidade. A Federação exige que
 * toda implementação seja entidade pela mesma chave; divergir aqui é erro de composição, não de
 * runtime. Quem resolve as três formas é o {@code UserEntityApi}.
 */
@Name("Author")
@Key(fields = @FieldSet("id"))
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
