package dev.manuelantunes.axonposts.interfaces.graphql;

import dev.manuelantunes.axonposts.dto.controller.PostView;
import dev.manuelantunes.axonposts.application.post.command.CreatePostCommand;
import dev.manuelantunes.axonposts.application.post.command.UpdatePostCommand;
import dev.manuelantunes.axonposts.application.post.query.FindPostQuery;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.dto.controller.CreatePostInput;
import dev.manuelantunes.axonposts.dto.controller.PostView;
import dev.manuelantunes.axonposts.dto.controller.UpdatePostInput;
import dev.manuelantunes.axonposts.mapper.PostInputMapper;
import jakarta.validation.Valid;
import org.axonframework.extension.reactor.messaging.commandhandling.gateway.ReactorCommandGateway;
import org.axonframework.extension.reactor.messaging.queryhandling.gateway.ReactorQueryGateway;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
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
 * <h2>Threading</h2>
 * O command só é enviado quando o {@code Mono} é assinado, e o {@code SimpleCommandBus} executa o
 * handler (e os event handlers, em modo subscribing) na thread que despacha — daí o
 * {@code boundedElastic}. Como o command handler salva o read model antes de commitar, dá para devolver
 * o Post já gravado com uma query logo em seguida.
 */
@Controller
public class PostMutationController {

    private final ReactorCommandGateway commandGateway;
    private final ReactorQueryGateway queryGateway;
    private final PostInputMapper inputMapper;

    public PostMutationController(ReactorCommandGateway commandGateway,
                                  ReactorQueryGateway queryGateway,
                                  PostInputMapper inputMapper) {
        this.commandGateway = commandGateway;
        this.queryGateway = queryGateway;
        this.inputMapper = inputMapper;
    }

    @MutationMapping
    public Mono<PostView> createPost(@Argument @Valid CreatePostInput input) {
        CreatePostCommand command = inputMapper.toCommand(PostId.newId(), input);
        return commandGateway.send(command, PostId.class)
                .flatMap(this::savedPost)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @MutationMapping
    public Mono<PostView> updatePost(@Argument @Valid UpdatePostInput input) {
        UpdatePostCommand command = inputMapper.toCommand(input);
        return commandGateway.send(command)
                .then(savedPost(command.postId()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<PostView> savedPost(PostId postId) {
        return queryGateway.query(new FindPostQuery(postId.value()), PostView.class);
    }
}
