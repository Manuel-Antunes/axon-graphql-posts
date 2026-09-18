package dev.manuelantunes.axonposts.application.post.view;

import java.time.Instant;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Ignore;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import io.smallrye.graphql.api.federation.FieldSet;
import io.smallrye.graphql.api.federation.Key;

/**
 * DTO de <b>saída</b> do GraphQL: os campos escalares do {@code type Post}, e nada além disso.
 * <p>
 * Não é um read model. O estado gravado é a própria entidade de domínio {@code Post}; este record existe
 * só para achatar os value objects em primitivos na borda, para que o schema não exponha
 * {@code {"title": {"value": "..."}}} e para que uma mudança no domínio não vire, sem querer, uma
 * mudança de contrato com o cliente.
 *
 * <h2>{@code @Name("Post")}, e o que ele substituiu</h2>
 * O schema é <b>code-first</b>: o tipo GraphQL sai da classe Java, então sem a anotação o type se
 * chamaria {@code PostView}. O nome {@code PostView} é útil de dentro — ele diz que aquilo é DTO de
 * saída, não a entidade {@code Post}, e num projeto onde os dois convivem essa distinção evita import
 * errado —, então a anotação reconcilia os dois lados numa linha.
 * <p>
 * É o mesmo problema que a versão Spring resolvia com um {@code ClassNameTypeResolver} configurado num
 * {@code @Bean}: lá o schema era escrito à mão e alguém tinha de contar ao graphql-java qual classe
 * correspondia a qual type. Aqui a resposta está na própria classe.
 *
 * <h2>As tags não estão aqui de propósito</h2>
 * Elas são um campo à parte no schema, resolvido em lote — carregar tudo junto seria exatamente o N+1
 * que o lote existe para evitar. Quem não pede {@code tags} na query não paga por elas.
 *
 * <h2>O autor é um id, e ele não aparece no schema</h2>
 * O campo {@code Post.author} é resolvido à parte, pelo mesmo lote de usuários que serve o {@code me} —
 * então uma resposta com N posts de M autores custa uma consulta de usuários, e o {@code AuthorView} que
 * sai dela é completo (e-mail, bio, contas).
 * <p>
 * Carregar o autor aqui dentro exigiria que a view viesse sempre completa, e o caminho da subscription
 * não tem como: ela monta o {@code PostView} a partir do payload do evento, que tem o id e nada mais. O
 * {@code @Ignore} esconde o {@code authorId} do schema sem escondê-lo do resolver, que recebe o objeto
 * inteiro.
 *
 * @param version quantidade de eventos aplicados a esse Post (1 = só criado). {@code int} e não
 *                {@code long} por causa do schema: a especificação MicroProfile GraphQL mapeia
 *                {@code long} para o scalar {@code BigInteger}, e um contador de versão é {@code Int!}
 *
 * <h2>{@code @Key(fields = "id")}: o que ele promete</h2>
 * Faz do {@code Post} uma <b>entidade</b> da Federação: um subgraph vizinho pode referenciá-lo tendo só
 * o id, e o roteador volta aqui pelo {@code _entities} para preencher o resto. Quem paga a promessa é o
 * {@code PostEntityApi} — a anotação sozinha compõe e quebra em runtime.
 * <p>
 * É a <b>mesma dívida consciente</b> do {@code @Name} logo acima, um passo adiante: a aplicação agora
 * sabe não só do protocolo, mas do papel deste tipo na topologia. Vale pelo mesmo motivo — separar
 * custaria uma segunda view por agregado — e some pela mesma fronteira, o {@code *ViewMapper}.
 */
@Name("Post")
@Key(fields = @FieldSet("id"))
@Description("Um post publicado, com o estado resultante de todos os eventos aplicados a ele")
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
