package dev.manuelantunes.axonposts.interfaces.graphql;

import dev.manuelantunes.axonposts.application.user.query.FindUsersByIdsQuery.FindUsersByIds;
import dev.manuelantunes.axonposts.application.user.query.FindUsersByIdsQuery.UsersById;
import dev.manuelantunes.axonposts.dto.controller.PostView;
import dev.manuelantunes.axonposts.dto.controller.UserView;
import graphql.schema.DataFetchingEnvironment;
import org.axonframework.extension.reactor.messaging.queryhandling.gateway.ReactorQueryGateway;
import org.dataloader.DataLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.stereotype.Controller;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * O campo {@code Post.author}, servido por DataLoader.
 *
 * <h2>Ele substituiu três lotes por um</h2>
 * Antes o {@code PostView} carregava um {@code AuthorView} com id e nome, e {@code email}, {@code bio} e
 * {@code accounts} eram resolvidos por três {@code @BatchMapping} distintos — três idas ao banco para
 * completar um objeto que o Hibernate já sabia montar inteiro.
 * <p>
 * Agora o {@code PostView} carrega só o {@code authorId} e este loader devolve a view <b>completa</b>,
 * numa consulta. A herança {@code JOINED} traz a bio no mesmo join e o {@code join fetch} traz as contas:
 * é a hidratação que o ORM já fazia, aproveitada em vez de refeita.
 *
 * <h2>Por que o autor não vem dentro do {@code PostView}</h2>
 * Porque nem sempre há de onde tirá-lo. O {@code PostCreatedEventHandler} monta a view a partir do
 * <b>payload do evento</b>, que tem o id do autor e mais nada — é o que permite a subscription responder
 * sem tocar no banco. Um campo resolvido sob demanda funciona nos dois caminhos; um campo dentro do
 * record obrigaria um deles a consultar.
 */
@Controller
public class PostAuthorController {

    private static final Logger log = LoggerFactory.getLogger(PostAuthorController.class);

    /** Nome no registry: o valor do loader é {@code UserView}, um tipo que o resolver do Spring não casa. */
    static final String LOADER = "Post.author";

    public PostAuthorController(ReactorQueryGateway queryGateway, BatchLoaderRegistry registry) {
        registry.<String, UserView>forName(LOADER).registerMappedBatchLoader(
                (authorIds, environment) -> {
                    log.debug("lote de autores: {} numa consulta", authorIds.size());
                    return queryGateway
                            .query(new FindUsersByIds(List.copyOf(authorIds)), UsersById.class)
                            .map(UsersById::byId)
                            // a query passa pelo bus, mas o handler por trás dela é JPA bloqueante
                            .subscribeOn(Schedulers.boundedElastic());
                });
    }

    /**
     * O schema declara {@code Post.author: Author!}, um tipo concreto — então o graphql-java nem chama o
     * resolver de tipo: resolve os campos direto no {@code AuthorView} que sai do lote.
     * <p>
     * Um autor apagado logicamente some do {@code @SQLRestriction} e não vem no mapa; o campo não-nulo
     * então falha, que é a resposta certa — um post cujo autor não existe mais não tem o que mostrar.
     */
    @SchemaMapping(typeName = "Post", field = "author")
    public CompletableFuture<UserView> author(PostView post, DataFetchingEnvironment environment) {
        DataLoader<String, UserView> loader = environment.getDataLoader(LOADER);
        return loader.load(post.authorId());
    }
}
