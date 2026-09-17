package dev.manuelantunes.axonposts.interfaces.graphql.api;

import java.util.List;
import java.util.Map;

import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Source;
import org.jboss.logging.Logger;

import dev.manuelantunes.axonposts.application.post.query.FindTagsByPostIdsQuery.FindTagsByPostIds;
import dev.manuelantunes.axonposts.application.post.query.FindTagsByPostIdsQuery.TagsByPost;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.application.tag.view.TagView;
import dev.manuelantunes.axonposts.application.user.query.FindUsersByIdsQuery.FindUsersByIds;
import dev.manuelantunes.axonposts.application.user.query.FindUsersByIdsQuery.UsersById;
import dev.manuelantunes.axonposts.application.user.view.AuthorView;
import dev.manuelantunes.axonposts.application.user.view.UserView;
import dev.manuelantunes.axonposts.interfaces.graphql.error.NotFoundException;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.ConnectionArgs;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.Connections;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.TagConnection;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.TagEdge;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Os campos de {@code Post} que não estão no {@link PostView}: {@code author} e {@code tags}. Os dois são
 * resolvidos <b>em lote</b>.
 *
 * <h2>{@code @Source List<T>} é o DataLoader</h2>
 * Receber a coleção em vez de um item só é o que faz o SmallRye registrar um {@code DataLoader} para o
 * campo: ele junta todos os posts do mesmo nível de execução e chama este método <b>uma vez</b>. O
 * contrato é devolver uma lista do mesmo tamanho e na mesma ordem da entrada.
 * <p>
 * Sem isso, {@code posts(first: 20) { edges { node { tags { … } } } }} dispararia 21 consultas: uma para
 * os posts e uma para as tags de cada um.
 *
 * <h2>Onde isto ficou mais simples que no Spring</h2>
 * Na versão Spring, estes dois campos <b>não podiam</b> usar a forma anotada: o {@code @BatchMapping}
 * não enxerga {@code @Argument} nem {@code ScrollSubrange}, e {@code tags} é paginado. A saída era a
 * forma explícita — registrar a função no {@code BatchLoaderRegistry} pelo construtor, nomear o loader
 * numa constante porque o resolver por tipo não casava {@code List}, e buscar o {@code DataLoader} no
 * {@code DataFetchingEnvironment} dentro do {@code @SchemaMapping}. Duas classes de cinquenta linhas,
 * quase todas de encanamento.
 * <p>
 * O {@code BatchDataFetcher} do SmallRye passa os argumentos do campo junto com as chaves no contexto do
 * lote, então um {@code @Source} em lote <b>pode</b> ter argumentos. As duas classes viram dois métodos.
 *
 * <h2>Threading</h2>
 * Os dois despachos passam pelo query bus, cujo handler é JPA bloqueante — daí o worker pool, como em
 * todo resolver deste projeto. Ver o {@code package-info} desta camada sobre por que o
 * {@code completionStage} recebe um {@code Supplier}.
 */
@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class PostFieldsApi {

    private static final Logger log = Logger.getLogger(PostFieldsApi.class);

    /** Prefixo do cursor da conexão de tags. */
    static final String TAG_CURSOR_TYPE = "tag";

    private final QueryGateway queryGateway;

    public PostFieldsApi(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    /**
     * {@code Post.author: Author!}, em lote.
     * <p>
     * O lote devolve a view <b>completa</b> (e-mail, bio, contas) numa consulta: a herança {@code JOINED}
     * traz a bio no mesmo join e o {@code join fetch} traz as contas. É a hidratação que o ORM já fazia,
     * aproveitada em vez de refeita — antes eram três lotes separados, um por campo.
     * <p>
     * O tipo declarado é {@link AuthorView} e não {@link UserView} porque o schema promete
     * {@code Author!}: só quem tem linha em {@code authors} pode escrever, e a chave estrangeira
     * {@code fk_posts_author} garante isso. Um autor apagado logicamente some do {@code @SQLRestriction}
     * e não vem no mapa — o campo não-nulo então falha, que é a resposta certa: um post cujo autor não
     * existe mais não tem o que mostrar.
     */
    @Name("author")
    @NonNull
    @Description("Quem escreveu. Resolvido em lote: N posts de M autores custam UMA consulta")
    public Uni<List<AuthorView>> author(@Source List<PostView> posts) {
        List<String> authorIds = posts.stream().map(PostView::authorId).distinct().toList();
        log.debugf("lote de autores: %d numa consulta", authorIds.size());

        return Uni.createFrom()
                .completionStage(() -> queryGateway.query(new FindUsersByIds(authorIds), UsersById.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(UsersById::byId)
                .map(byId -> posts.stream().map(post -> author(byId, post)).toList());
    }

    /**
     * {@code Post.tags(first: Int, after: String): TagConnection!}, em lote.
     * <p>
     * O lote traz todas as tags de cada post e a paginação recorta em memória. É o certo para uma coleção
     * filha pequena: ela já veio inteira no {@code join fetch}, e paginar no banco por post desfaria o
     * lote. Para uma coleção grande, o caminho seria uma janela por chave (window function) dentro da
     * própria consulta — e a fronteira deste método não mudaria.
     * <p>
     * Um post criado sem tags recebe automaticamente a tag padrão {@code Untagged}, o que gera um
     * {@code PostUpdated} — por isso um post recém-criado chega aqui já na versão 2.
     */
    @Name("tags")
    @NonNull
    @Description("Tags do post, como Relay cursor connection. Resolvidas em lote")
    public Uni<List<TagConnection>> tags(
            @Source List<PostView> posts,
            @Name("first") @Description("Quantas tags trazer; ausente = 20") Integer first,
            @Name("after") @Description("Cursor da última tag já vista; ausente = do começo") String after) {

        ConnectionArgs args = ConnectionArgs.of(TAG_CURSOR_TYPE, first, after);
        List<String> postIds = posts.stream().map(PostView::id).toList();
        log.debugf("lote de tags: %d post(s) numa consulta", postIds.size());

        return Uni.createFrom()
                .completionStage(() -> queryGateway.query(new FindTagsByPostIds(postIds), TagsByPost.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(TagsByPost::byPostId)
                .map(byPostId -> posts.stream().map(post -> connection(byPostId, post, args)).toList());
    }

    /** Post sem tag nenhuma não vem no mapa do lote — daí o {@code null} virar lista vazia. */
    private static TagConnection connection(Map<String, List<TagView>> byPostId,
                                            PostView post,
                                            ConnectionArgs args) {
        List<TagView> tags = byPostId.getOrDefault(post.id(), List.of());
        return Connections.slice(tags, args, TagEdge::new, TagConnection::new);
    }

    private static AuthorView author(Map<String, UserView> byId, PostView post) {
        UserView user = byId.get(post.authorId());
        if (user instanceof AuthorView author) {
            return author;
        }
        throw new NotFoundException("o autor do post " + post.id() + " não está mais disponível");
    }
}
