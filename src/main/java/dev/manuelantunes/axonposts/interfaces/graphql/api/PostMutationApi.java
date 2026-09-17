package dev.manuelantunes.axonposts.interfaces.graphql.api;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import dev.manuelantunes.axonposts.application.auth.AuthenticatedUser;
import dev.manuelantunes.axonposts.application.post.command.DeletePostCommand.DeletePost;
import dev.manuelantunes.axonposts.application.post.command.RestorePostCommand.RestorePost;
import dev.manuelantunes.axonposts.application.post.query.FindPostQuery.FindPost;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.user.Role;
import dev.manuelantunes.axonposts.interfaces.graphql.dto.CreatePostInput;
import dev.manuelantunes.axonposts.interfaces.graphql.dto.UpdatePostInput;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import dev.manuelantunes.axonposts.interfaces.graphql.mapper.PostInputMapper;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;

/**
 * Camada de interface das <b>mutations</b> GraphQL: valida o input, traduz para command e despacha pelo
 * {@link CommandGateway}.
 *
 * <h2>{@code @Valid}</h2>
 * É o que liga a Bean Validation: o Quarkus valida o parâmetro antes de o método rodar, e um input
 * inválido vira {@code ConstraintViolationException} <b>antes</b> de o command existir.
 * <p>
 * Quem a classifica em {@code BAD_REQUEST} é o {@code @TranslatesErrors} da classe. O interceptador de
 * validação do Quarkus roda <b>dentro</b> do de aplicação, então a violação sobe por ele — o que não
 * acontecia quando a tradução era uma chamada no corpo do método, e por isso um input inválido saía como
 * {@code ValidationError} sem {@code extensions.code}.
 *
 * <h2>Autorização: {@code @RolesAllowed} + {@code requireAuthor()}, e por que os dois</h2>
 * O {@code @RolesAllowed("author")} decide pela claim do token, sem tocar no banco — barra cedo e barato.
 * O {@code currentUser.requireAuthor()} carrega a entidade e confirma o tipo contra a tabela
 * {@code authors}.
 * <p>
 * Não é redundância: são duas perguntas diferentes. A primeira é "este token afirma ser de um autor?"; a
 * segunda é "este usuário <b>é</b> um autor, agora?". Um token emitido antes de o papel ser revogado
 * responde sim à primeira e não à segunda — e é a segunda que decide, porque é ela que devolve o objeto
 * {@code Author} que o command precisa.
 * <p>
 * O que muda em relação ao {@code @PreAuthorize("hasRole('AUTHOR')")} do Spring é só a grafia: o Quarkus
 * usa a anotação padrão do Jakarta Security e não acrescenta prefixo nenhum, então o literal é
 * exatamente o que o realm do Keycloak emite. A constante {@link Role#AUTHOR_CLAIM} existe porque
 * anotação exige literal em tempo de compilação.
 *
 * <h2>Threading</h2>
 * O command só é enviado quando o {@code Uni} é assinado, e o {@code SimpleCommandBus} executa o handler
 * (e os event handlers, em modo subscribing) na thread que despacha — daí o worker pool. Como o command
 * salva o read model antes de commitar, dá para devolver o Post já gravado com uma query logo em
 * seguida.
 */
@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class PostMutationApi {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final PostInputMapper inputMapper;
    private final AuthenticatedUser currentUser;

    public PostMutationApi(CommandGateway commandGateway,
                           QueryGateway queryGateway,
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
    @Mutation("createPost")
    @NonNull
    @RolesAllowed(Role.AUTHOR_CLAIM)
    @Description("Despacha o command CreatePost e devolve o Post já projetado")
    public Uni<PostView> createPost(@Name("input") @NonNull @Valid CreatePostInput input) {
        return currentUser.requireAuthor()
                .map(author -> inputMapper.toCommand(PostId.newId(), input, author.id()))
                .flatMap(command -> Uni.createFrom()
                        .completionStage(() -> commandGateway.send(command, PostId.class))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                        .flatMap(this::savedPost));
    }

    @Mutation("updatePost")
    @NonNull
    @RolesAllowed(Role.AUTHOR_CLAIM)
    @Description("Despacha o command UpdatePost e devolve o Post já projetado")
    public Uni<PostView> updatePost(@Name("input") @NonNull @Valid UpdatePostInput input) {
        return currentUser.requireAuthor()
                .map(author -> inputMapper.toCommand(input, author.id()))
                .flatMap(command -> Uni.createFrom()
                        .completionStage(() -> commandGateway.send(command, Void.class))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                        .flatMap(ignored -> savedPost(command.postId())));
    }

    /**
     * Apaga logicamente. Devolve {@code true} porque o post, depois disto, não é mais consultável — uma
     * mutation que tentasse devolver {@code Post!} teria de furar o próprio filtro que acabou de aplicar.
     */
    @Mutation("deletePost")
    @NonNull
    @RolesAllowed(Role.AUTHOR_CLAIM)
    @Description("Exclusão lógica: o post some das consultas, mas a linha e o stream continuam lá")
    public Uni<Boolean> deletePost(@Name("id") @Id @NonNull String id) {
        return currentUser.requireAuthor()
                .flatMap(author -> Uni.createFrom()
                        .completionStage(() -> commandGateway.send(
                                new DeletePost(PostId.of(id), author.id()), Void.class))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
                .replaceWith(Boolean.TRUE);
    }

    /** Restaura e devolve o post — que a esta altura já voltou a ser consultável. */
    @Mutation("restorePost")
    @NonNull
    @RolesAllowed(Role.AUTHOR_CLAIM)
    @Description("Desfaz a exclusão lógica. Funciona porque o agregado é reidratado dos eventos")
    public Uni<PostView> restorePost(@Name("id") @Id @NonNull String id) {
        PostId postId = PostId.of(id);
        return currentUser.requireAuthor()
                .flatMap(author -> Uni.createFrom()
                        .completionStage(() -> commandGateway.send(
                                new RestorePost(postId, author.id()), Void.class))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
                .flatMap(ignored -> savedPost(postId));
    }

    private Uni<PostView> savedPost(PostId postId) {
        return Uni.createFrom()
                .completionStage(() -> queryGateway.query(new FindPost(postId.value()), PostView.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
