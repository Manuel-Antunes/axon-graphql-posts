package dev.manuelantunes.axonposts.interfaces.graphql;

import dev.manuelantunes.axonposts.application.auth.AuthenticationService;
import dev.manuelantunes.axonposts.dto.controller.AuthPayload;
import dev.manuelantunes.axonposts.dto.controller.LoginInput;
import dev.manuelantunes.axonposts.mapper.UserViewMapper;
import jakarta.validation.Valid;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A mutation {@code login}: a única operação sem {@code @PreAuthorize} da aplicação, por definição —
 * é ela que produz a credencial que as outras exigem.
 * <p>
 * O {@code boundedElastic} é pelo mesmo motivo de sempre: dentro há JPA bloqueante (a busca do usuário)
 * e um BCrypt, que é caro <i>de propósito</i> e não pode rodar no event-loop.
 */
@Controller
public class AuthMutationController {

    private final AuthenticationService authentication;
    private final UserViewMapper userViewMapper;

    public AuthMutationController(AuthenticationService authentication, UserViewMapper userViewMapper) {
        this.authentication = authentication;
        this.userViewMapper = userViewMapper;
    }

    @MutationMapping
    public Mono<AuthPayload> login(@Argument @Valid LoginInput input) {
        return Mono.fromCallable(() -> authentication.login(input.email(), input.password()))
                .map(session -> new AuthPayload(
                        session.token().value(),
                        session.token().expiresAt(),
                        userViewMapper.toView(session.user())))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
