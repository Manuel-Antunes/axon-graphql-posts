package dev.manuelantunes.axonposts.interfaces.graphql.api;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.NonNull;

import dev.manuelantunes.axonposts.application.auth.AuthenticatedUser;
import dev.manuelantunes.axonposts.application.user.command.DeleteUserCommand.DeleteUser;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * As mutations sobre a <b>própria</b> conta.
 *
 * <h2>Autorização por identidade, não por papel</h2>
 * {@code deleteMe} não pede a role de autor nem nada além de estar autenticado: o alvo é sempre quem está
 * pedindo. Não existe argumento de id, e é isso que torna a operação segura — não há como apagar a conta
 * de outro porque não há como <b>nomear</b> a conta de outro.
 * <p>
 * É o mesmo princípio do {@code createPost}, que tira o autor do token em vez do input, aplicado ao caso
 * mais sensível.
 */
@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class MeMutationApi {

    private final CommandGateway commandGateway;
    private final AuthenticatedUser currentUser;

    public MeMutationApi(CommandGateway commandGateway, AuthenticatedUser currentUser) {
        this.commandGateway = commandGateway;
        this.currentUser = currentUser;
    }

    /**
     * Exclusão lógica: o usuário some das consultas, e entrar de novo com a mesma credencial reativa a
     * conta ({@code UserProvisioning}). Os posts dele somem junto — ver {@code DeleteUserCommand}.
     */
    @Mutation("deleteMe")
    @NonNull
    @Authenticated
    @Description("Apaga a própria conta (exclusão lógica). Exige apenas estar autenticado")
    public Uni<Boolean> deleteMe() {
        return currentUser.require()
                .flatMap(user -> Uni.createFrom()
                        .completionStage(() -> commandGateway.send(new DeleteUser(user.id()), Void.class))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
                .replaceWith(Boolean.TRUE);
    }
}
