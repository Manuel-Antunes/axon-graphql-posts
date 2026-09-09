package dev.manuelantunes.axonposts.infrastructure.security;

import dev.manuelantunes.axonposts.application.auth.AuthenticatedUser;
import dev.manuelantunes.axonposts.application.auth.Identity;
import dev.manuelantunes.axonposts.application.auth.UserProvisioning;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.exception.NotAnAuthorException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * O adapter de {@link AuthenticatedUser}: traduz o token do Keycloak no usuário de domínio.
 * <p>
 * É a metade "como" da porta — e é infraestrutura justamente porque conhece {@code Jwt},
 * {@code ReactiveSecurityContextHolder} e o formato das claims. Nenhum controller importa esta classe.
 *
 * <h2>Do token do Keycloak para o usuário local</h2>
 * O {@code sub} do token identifica a pessoa <b>no Keycloak</b>. A ponte até o {@code User} daqui é a
 * tabela {@code accounts}: o par {@code (provider, sub)} é a chave, e é o {@link UserProvisioning} que a
 * resolve — criando ou ligando o usuário na primeira vez que aquele token aparece.
 * <p>
 * Antes da migração este método fazia {@code users.findById(sub)}, porque o {@code sub} <b>era</b> o id
 * local. Agora são dois espaços de identidade distintos, e confundi-los seria amarrar as chaves
 * primárias da aplicação às do broker — que é exatamente o que a tabela de contas existe para evitar.
 *
 * <h2>O tipo continua vindo do banco</h2>
 * A role {@code author} do token diz o que a pessoa <i>pode</i>; a linha em {@code authors} diz o que ela
 * <i>é</i>. {@link #requireAuthor()} continua confirmando com {@code instanceof}, e o provisionamento
 * cuida de manter as duas em dia.
 */
@Component
public class CurrentUser implements AuthenticatedUser {

    private final UserProvisioning provisioning;

    public CurrentUser(UserProvisioning provisioning) {
        this.provisioning = provisioning;
    }

    /** O token cru de quem está autenticado; erro se a requisição for anônima. */
    public Mono<Jwt> token() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .switchIfEmpty(Mono.error(() -> new AuthenticationCredentialsNotFoundException(
                        "requisição sem token: mande Authorization: Bearer <token do Keycloak>")));
    }

    @Override
    public Mono<User> require() {
        return token().flatMap(jwt -> Mono
                .fromCallable(() -> provisioning.provision(identityOf(jwt)))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * O usuário autenticado <b>como autor</b>. O {@code @PreAuthorize("hasRole('AUTHOR')")} do controller
     * já barrou pela role do token; este {@code instanceof} confirma contra o banco.
     */
    @Override
    public Mono<Author> requireAuthor() {
        return require().flatMap(user -> user instanceof Author author
                ? Mono.just(author)
                : Mono.error(new NotAnAuthorException(user.id())));
    }

    /**
     * Claims → {@link Identity}. É aqui, e só aqui, que o formato do token do Keycloak é
     * conhecido.
     * <p>
     * {@code identity_provider} aparece quando o Keycloak intermediou um login social: o provedor
     * registrado passa a ser o de origem ({@code GOOGLE}, {@code GITHUB}), não o broker. É o que faz duas
     * entradas diferentes da mesma pessoa virarem duas linhas em {@code accounts} — e o account linking
     * ter o que ligar.
     */
    private static Identity identityOf(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        String name = jwt.getClaimAsString("name");

        return new Identity(
                AuthProvider.fromAlias(jwt.getClaimAsString("identity_provider")),
                jwt.getSubject(),
                email != null ? email : preferredUsername,
                name != null && !name.isBlank() ? name : preferredUsername,
                KeycloakRealmRolesConverter.hasAuthorRole(jwt)
        );
    }
}
