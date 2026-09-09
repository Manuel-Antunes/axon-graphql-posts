package dev.manuelantunes.axonposts.interfaces.graphql;

import dev.manuelantunes.axonposts.dto.controller.UserView;
import dev.manuelantunes.axonposts.application.auth.AuthenticatedUser;
import dev.manuelantunes.axonposts.mapper.UserViewMapper;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

/**
 * A query {@code me}: quem está logado.
 *
 * <h2>O ponto polimórfico do schema</h2>
 * O retorno declarado é {@code User}, a interface. Quem decide se o cliente recebe um {@code Reader} ou
 * um {@code Author} é o tipo que saiu do banco — o {@code UserViewMapper} despacha por
 * {@code instanceof}, e o {@code ClassNameTypeResolver} traduz a classe do DTO no nome do schema.
 * <p>
 * Ou seja: {@code me { ... on Author { bio } }} só traz {@code bio} para quem tem linha em
 * {@code authors}. Não há campo a forjar no token — a claim {@code roles} serve para <i>barrar</i>
 * cedo, mas o que <i>aparece</i> na resposta vem da hierarquia real.
 *
 * <h2>{@code @PreAuthorize("isAuthenticated()")} e o retorno não-nulo</h2>
 * O schema declara {@code me: User!}. Sem a anotação, um anônimo chegaria ao {@code CurrentUser} e
 * receberia o erro de lá — que é o mesmo resultado, só que uma camada mais fundo e depois de já ter
 * entrado no controller. Barrar aqui deixa a exigência visível ao lado da assinatura.
 */
@Controller
public class MeQueryController {

    private final AuthenticatedUser currentUser;
    private final UserViewMapper userViewMapper;

    public MeQueryController(AuthenticatedUser currentUser, UserViewMapper userViewMapper) {
        this.currentUser = currentUser;
        this.userViewMapper = userViewMapper;
    }

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public Mono<UserView> me() {
        return currentUser.require().map(userViewMapper::toView);
    }
}
