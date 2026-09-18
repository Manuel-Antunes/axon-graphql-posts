package dev.manuelantunes.axonposts.application.user.view;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import io.smallrye.graphql.api.federation.FieldSet;
import io.smallrye.graphql.api.federation.Key;

/**
 * DTO de saída de um usuário que não escreve: o {@code type Reader} do schema.
 * <p>
 * O nome bate com a classe de domínio {@code Reader} — os dois nasceram junto, quando o {@code User}
 * virou raiz abstrata e "ser leitor" deixou de ser a ausência de linha em {@code authors}.
 * <p>
 * As anotações repetem as de {@link UserView} de propósito: num schema GraphQL o campo do tipo concreto
 * precisa ser <b>compatível</b> com o da interface, então {@code id} tem de ser {@code ID!} nos dois
 * lados. Declarar num só daria um schema que não valida.
 *
 * <h2>A chave é a MESMA da interface, e isso é obrigação</h2>
 * {@code UserView} leva {@code @Key(fields = "id")} — é interface de entidade. A Federação exige que
 * toda implementação seja entidade pela mesma chave; divergir aqui é erro de composição, não de
 * runtime. Quem resolve as três formas é o {@code UserEntityApi}.
 */
@Name("Reader")
@Key(fields = @FieldSet("id"))
@Description("Usuário que só lê. É o `User` do Java sem linha na tabela `authors`")
public record ReaderView(
        @Id @NonNull String id,
        @NonNull String name,
        @NonNull String email,
        @NonNull List<AccountView> accounts
) implements UserView {
}
