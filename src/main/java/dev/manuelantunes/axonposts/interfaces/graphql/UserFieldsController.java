package dev.manuelantunes.axonposts.interfaces.graphql;

import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.dto.controller.AuthorView;
import dev.manuelantunes.axonposts.dto.controller.UserView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Os campos de {@code User}/{@code Author} que não vêm no DTO: {@code email} e {@code bio}.
 *
 * <h2>Interface schema mapping</h2>
 * {@link #email} é mapeado em {@code typeName = "User"} — a <b>interface</b>. O Spring GraphQL registra
 * o data fetcher para toda implementação que não declare o seu próprio, então um único método resolve
 * {@code Reader.email} e {@code Author.email}. Sem isso seriam dois métodos idênticos, e um terceiro no
 * dia que aparecesse um terceiro tipo.
 *
 * <h2>Aqui {@code @BatchMapping} serve, e no {@code Post.tags} não</h2>
 * A diferença é uma só: <b>argumentos</b>. Um método {@code @BatchMapping} recebe a coleção de fontes e
 * nada mais — não há {@code @Argument} nem {@code ScrollSubrange}. {@code email} e {@code bio} são
 * escalares sem argumento, então a forma anotada dá conta e economiza o registro manual no
 * {@code BatchLoaderRegistry}. {@code Post.tags} e {@code Author.posts} são paginados, e por isso
 * precisam da forma explícita.
 * <p>
 * O ganho é o mesmo dos outros lotes: uma resposta com N autores faz <b>uma</b> consulta, não N.
 *
 * <h2>Por que o DTO não carrega esses campos desde o começo</h2>
 * Porque nem sempre há de onde tirá-los. Um {@code AuthorView} servido por
 * {@code onPostCreated} é montado a partir do payload do evento, que tem id e nome — não tem e-mail nem
 * bio. Um campo resolvido sob demanda funciona nos dois caminhos; um campo no record teria de vir nulo
 * num deles.
 */
@Controller
public class UserFieldsController {

    private static final Logger log = LoggerFactory.getLogger(UserFieldsController.class);

    private final UserRepository users;

    public UserFieldsController(UserRepository users) {
        this.users = users;
    }

    /**
     * {@code User.email} — na interface, então vale para {@code Reader} e {@code Author}.
     * <p>
     * O {@code Map} devolvido é indexado pelo próprio objeto-fonte; os DTOs são records, então
     * {@code equals}/{@code hashCode} vêm prontos e corretos.
     */
    @BatchMapping(typeName = "User", field = "email")
    public Mono<Map<UserView, String>> email(List<UserView> sources) {
        return load(sources, user -> user.email().value());
    }

    /** {@code Author.bio} — só a subclasse tem, então o mapeamento é no tipo concreto. */
    @BatchMapping(typeName = "Author", field = "bio")
    public Mono<Map<AuthorView, String>> bio(List<AuthorView> sources) {
        return load(sources, user -> user instanceof Author author ? author.bio() : "—");
    }

    /**
     * O lote propriamente dito: uma consulta com todos os ids da resposta, e um {@code Map} de volta
     * indexado pela fonte. Fontes cujo usuário sumiu do banco ficam de fora do mapa — o graphql-java
     * trata a ausência como {@code null}, que num campo {@code String!} vira erro de campo, e não uma
     * resposta silenciosamente errada.
     */
    private <V extends UserView> Mono<Map<V, String>> load(List<V> sources, Function<User, String> field) {
        return Mono.fromCallable(() -> {
                    log.debug("lote de usuários: {} numa consulta", sources.size());

                    Map<String, User> byId = users
                            .findAllById(sources.stream().map(source -> UserId.of(source.id())).toList())
                            .stream()
                            .collect(Collectors.toMap(user -> user.id().value(), Function.identity()));

                    return sources.stream()
                            .filter(source -> byId.containsKey(source.id()))
                            .collect(Collectors.toMap(
                                    Function.identity(),
                                    source -> field.apply(byId.get(source.id())),
                                    (first, second) -> first));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
