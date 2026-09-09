package dev.manuelantunes.axonposts.interfaces.graphql;

import dev.manuelantunes.axonposts.dto.controller.PostView;
import dev.manuelantunes.axonposts.application.post.command.CreatePostCommand.CreatePost;
import dev.manuelantunes.axonposts.application.post.command.DeletePostCommand.DeletePost;
import dev.manuelantunes.axonposts.application.post.command.RestorePostCommand.RestorePost;
import dev.manuelantunes.axonposts.application.post.command.UpdatePostCommand.UpdatePost;
import dev.manuelantunes.axonposts.application.post.query.FindPostQuery.FindPost;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.dto.controller.CreatePostInput;
import dev.manuelantunes.axonposts.dto.controller.UpdatePostInput;
import dev.manuelantunes.axonposts.application.auth.AuthenticatedUser;
import dev.manuelantunes.axonposts.mapper.PostInputMapper;
import jakarta.validation.Valid;
import org.axonframework.extension.reactor.messaging.commandhandling.gateway.ReactorCommandGateway;
import org.axonframework.extension.reactor.messaging.queryhandling.gateway.ReactorQueryGateway;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Camada de interface das <b>mutations</b> GraphQL: valida o input, traduz para command e despacha pelo
 * {@link ReactorCommandGateway}.
 *
 * <h2>{@code @Valid}</h2>
 * É o que liga a Bean Validation: o {@code AnnotatedControllerConfigurer} do Spring GraphQL monta um
 * validador para o método quando encontra {@code @Valid} (ou uma constraint) num parâmetro e um bean
 * {@code jakarta.validation.Validator} no contexto — que o {@code spring-boot-starter-validation}
 * fornece. Um input inválido vira {@code ConstraintViolationException} <b>antes</b> do command existir,
 * e o {@code AppGraphQlExceptionHandler} a traduz em {@code BAD_REQUEST}.
 *
 * <h2>Autorização: {@code @PreAuthorize} + {@code CurrentUser}, e por que os dois</h2>
 * O {@code @PreAuthorize("hasRole('AUTHOR')")} decide pela claim do token, sem tocar no banco — barra
 * cedo e barato. O {@code currentUser.requireAuthor()} carrega a entidade e confirma o tipo contra a
 * tabela {@code authors}.
 * <p>
 * Não é redundância: são duas perguntas diferentes. A primeira é "este token afirma ser de um autor?"; a
 * segunda é "este usuário <b>é</b> um autor, agora?". Um token emitido antes de o papel ser revogado
 * responde sim à primeira e não à segunda — e é a segunda que decide, porque é ela que devolve o objeto
 * {@code Author} que o command precisa.
 * <p>
 * O que o {@code @PreAuthorize} garante na prática é que o caminho de erro do {@code requireAuthor()}
 * seja raro: quando o método roda, o upcast quase sempre já está justificado.
 *
 * <h2>Threading</h2>
 * O command só é enviado quando o {@code Mono} é assinado, e o {@code SimpleCommandBus} executa o
 * handler (e os event handlers, em modo subscribing) na thread que despacha — daí o
 * {@code boundedElastic}. Como o command salva o read model antes de commitar, dá para devolver
 * o Post já gravado com uma query logo em seguida.
 */
@Controller
public class PostMutationController {

    private final ReactorCommandGateway commandGateway;
    private final ReactorQueryGateway queryGateway;
    private final PostInputMapper inputMapper;
    private final AuthenticatedUser currentUser;

    // o gateway vem do registry de componentes do Axon, não de um @Bean: a inspeção do IDE não o vê
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public PostMutationController(ReactorCommandGateway commandGateway,
                                  ReactorQueryGateway queryGateway,
                                  PostInputMapper inputMapper,
                                  AuthenticatedUser currentUser) {
        this.commandGateway = commandGateway;
        this.queryGateway = queryGateway;
        this.inputMapper = inputMapper;
        this.currentUser = currentUser;
    }

    /**
     * O autor sai de {@code currentUser.requireAuthor()} e entra no command como {@code authorId}. O
     * input não tem esse campo, e é por isso que não há como publicar em nome de outro.
     */
    @MutationMapping
    @PreAuthorize("hasRole('AUTHOR')")
    public Mono<PostView> createPost(@Argument @Valid CreatePostInput input) {
        return currentUser.requireAuthor()
                .map(author -> inputMapper.toCommand(PostId.newId(), input, author.id()))
                .flatMap(command -> commandGateway.send(command, PostId.class))
                .flatMap(this::savedPost)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @MutationMapping
    @PreAuthorize("hasRole('AUTHOR')")
    public Mono<PostView> updatePost(@Argument @Valid UpdatePostInput input) {
        return currentUser.requireAuthor()
                .map(author -> inputMapper.toCommand(input, author.id()))
                .flatMap(command -> commandGateway.send(command).then(savedPost(command.postId())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Apaga logicamente. Devolve {@code true} porque o post, depois disto, não é mais consultável — uma
     * mutation que tentasse devolver {@code Post!} teria de furar o próprio filtro que acabou de aplicar.
     */
    @MutationMapping
    @PreAuthorize("hasRole('AUTHOR')")
    public Mono<Boolean> deletePost(@Argument String id) {
        return currentUser.requireAuthor()
                .flatMap(author -> commandGateway.send(new DeletePost(PostId.of(id), author.id())))
                .thenReturn(true)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Restaura e devolve o post — que a esta altura já voltou a ser consultável. */
    @MutationMapping
    @PreAuthorize("hasRole('AUTHOR')")
    public Mono<PostView> restorePost(@Argument String id) {
        PostId postId = PostId.of(id);
        return currentUser.requireAuthor()
                .flatMap(author -> commandGateway.send(new RestorePost(postId, author.id())))
                .then(savedPost(postId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<PostView> savedPost(PostId postId) {
        return queryGateway.query(new FindPost(postId.value()), PostView.class);
    }
}
