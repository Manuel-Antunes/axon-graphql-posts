package dev.manuelantunes.axonposts.interfaces.graphql;

import dev.manuelantunes.axonposts.application.auth.AuthenticatedUser;
import dev.manuelantunes.axonposts.application.user.command.DeleteUserCommand.DeleteUser;
import org.axonframework.extension.reactor.messaging.commandhandling.gateway.ReactorCommandGateway;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * As mutations sobre a <b>própria</b> conta.
 *
 * <h2>Autorização por identidade, não por papel</h2>
 * {@code deleteMe} não pede {@code ROLE_AUTHOR} nem nada além de estar autenticado: o alvo é sempre quem
 * está pedindo. Não existe argumento de id, e é isso que torna a operação segura — não há como apagar a
 * conta de outro porque não há como <b>nomear</b> a conta de outro.
 * <p>
 * É o mesmo princípio do {@code createPost}, que tira o autor do token em vez do input, aplicado ao caso
 * mais sensível.
 */
@Controller
public class MeMutationController {

    private final ReactorCommandGateway commandGateway;
    private final AuthenticatedUser currentUser;

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public MeMutationController(ReactorCommandGateway commandGateway, AuthenticatedUser currentUser) {
        this.commandGateway = commandGateway;
        this.currentUser = currentUser;
    }

    /**
     * Exclusão lógica: o usuário some das consultas, os posts dele continuam publicados, e entrar de novo
     * com a mesma credencial reativa a conta ({@code UserProvisioning}).
     */
    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public Mono<Boolean> deleteMe() {
        return currentUser.require()
                .flatMap(user -> commandGateway.send(new DeleteUser(user.id())))
                .thenReturn(true)
                .subscribeOn(Schedulers.boundedElastic());
    }
}
