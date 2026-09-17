package dev.manuelantunes.axonposts.interfaces.graphql.api;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

import dev.manuelantunes.axonposts.application.auth.AuthenticatedUser;
import dev.manuelantunes.axonposts.application.user.view.UserView;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import dev.manuelantunes.axonposts.application.user.view.UserViewMapper;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A query {@code me}: quem está logado.
 *
 * <h2>O ponto polimórfico do schema</h2>
 * O retorno declarado é {@link UserView}, a interface — e no schema ela é {@code interface User}. Quem
 * decide se o cliente recebe um {@code Reader} ou um {@code Author} é o tipo que saiu do banco: o
 * {@code UserViewMapper} despacha por {@code instanceof}, e o {@code @Name} de cada implementação diz
 * qual type do schema ela é.
 * <p>
 * Ou seja: {@code me { ... on Author { bio } }} só traz {@code bio} para quem tem linha em
 * {@code authors}. Não há campo a forjar no token — a claim {@code roles} serve para <i>barrar</i> cedo,
 * mas o que <i>aparece</i> na resposta vem da hierarquia real.
 *
 * <h2>{@code @Authenticated} e o retorno não-nulo</h2>
 * O schema declara {@code me: User!}. Sem a anotação, um anônimo chegaria ao {@code CurrentUser} e
 * receberia o erro de lá — que é o mesmo resultado, só que uma camada mais fundo e depois de já ter
 * entrado no resolver. Barrar aqui deixa a exigência visível ao lado da assinatura. É o equivalente
 * exato do {@code @PreAuthorize("isAuthenticated()")} do Spring.
 */
@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class MeQueryApi {

    private final AuthenticatedUser currentUser;
    private final UserViewMapper userViewMapper;

    public MeQueryApi(AuthenticatedUser currentUser, UserViewMapper userViewMapper) {
        this.currentUser = currentUser;
        this.userViewMapper = userViewMapper;
    }

    @Query("me")
    @NonNull
    @Authenticated
    @Description("Quem está logado. Exige Authorization: Bearer <token emitido pelo Keycloak>")
    public Uni<UserView> me() {
        return currentUser.require().map(userViewMapper::toView);
    }
}
