package dev.manuelantunes.axonposts.infrastructure.security;

import java.util.Optional;

import org.eclipse.microprofile.jwt.JsonWebToken;

import dev.manuelantunes.axonposts.application.auth.AuthenticatedUser;
import dev.manuelantunes.axonposts.application.auth.Identity;
import dev.manuelantunes.axonposts.application.auth.UserProvisioning;
import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.Role;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.exception.NotAnAuthorException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * O adapter de {@link AuthenticatedUser}: traduz o token do Keycloak no usuário de domínio.
 * <p>
 * É a metade "como" da porta — e é infraestrutura justamente porque conhece {@link SecurityIdentity},
 * {@link JsonWebToken} e o formato das claims. Nenhum resolver GraphQL importa esta classe.
 *
 * <h2>Do token do Keycloak para o usuário local</h2>
 * O {@code sub} do token identifica a pessoa <b>no Keycloak</b>. A ponte até o {@code User} daqui é a
 * tabela {@code accounts}: o par {@code (provider, sub)} é a chave, e é o {@link UserProvisioning} que a
 * resolve — criando ou ligando o usuário na primeira vez que aquele token aparece.
 * <p>
 * São dois espaços de identidade distintos, e confundi-los seria amarrar as chaves primárias da
 * aplicação às do broker — que é exatamente o que a tabela de contas existe para evitar.
 *
 * <h2>Identidade preguiçosa, e por quê</h2>
 * A aplicação roda com {@code quarkus.http.auth.proactive-authentication=false}: o Quarkus <b>não</b>
 * autentica toda requisição que chega, porque um endpoint GraphQL é um caminho HTTP só e há operações
 * públicas ({@code post}, {@code posts}) e privadas no mesmo POST. Quem exige autenticação é o método,
 * pelo {@code @RolesAllowed} — o equivalente exato do {@code permitAll} na cadeia + {@code @PreAuthorize}
 * do projeto Spring.
 * <p>
 * A consequência para este código é que não se injeta {@code SecurityIdentity} direto: pede-se a
 * identidade <b>diferida</b> ao {@link CurrentIdentityAssociation}, que a resolve quando alguém a
 * assinar. Um anônimo chega aqui com uma identidade anônima, e não com uma exceção.
 *
 * <h2>Onde o trabalho bloqueante acontece</h2>
 * Resolver a identidade é barato e não bloqueia — fica no event-loop. O provisionamento lê e escreve no
 * Postgres com JPA bloqueante, então <b>só ele</b> é empurrado para o worker pool. É a mesma divisão que
 * o {@code subscribeOn(boundedElastic())} do projeto Spring fazia, só que restrita à parte que precisa.
 */
@ApplicationScoped
public class CurrentUser implements AuthenticatedUser {

    /** Claim que o Keycloak escreve quando ele intermediou um login social. */
    static final String IDENTITY_PROVIDER = "identity_provider";

    private final UserProvisioning provisioning;
    private final CurrentIdentityAssociation identityAssociation;

    public CurrentUser(UserProvisioning provisioning, CurrentIdentityAssociation identityAssociation) {
        this.provisioning = provisioning;
        this.identityAssociation = identityAssociation;
    }

    @Override
    public Uni<User> require() {
        return identityAssociation.getDeferredIdentity()
                .onItem().transform(CurrentUser::identityOf)
                .onItem().transformToUni(identity -> Uni.createFrom()
                        .item(() -> provisioning.provision(identity))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool()));
    }

    /**
     * O usuário autenticado <b>como autor</b>. O {@code @RolesAllowed("author")} do resolver já barrou
     * pela role do token; este {@code instanceof} confirma contra o banco, que é a verdade final.
     */
    @Override
    public Uni<Author> requireAuthor() {
        return require().onItem().transform(user -> {
            if (user instanceof Author author) {
                return author;
            }
            throw new NotAnAuthorException(user.id());
        });
    }

    /**
     * Claims → {@link Identity}. É aqui, e só aqui, que o formato do token do Keycloak é conhecido.
     * <p>
     * {@code identity_provider} aparece quando o Keycloak intermediou um login social: o provedor
     * registrado passa a ser o de origem ({@code GOOGLE}, {@code GITHUB}), não o broker. É o que faz duas
     * entradas diferentes da mesma pessoa virarem duas linhas em {@code accounts} — e o account linking
     * ter o que ligar.
     *
     * @throws UnauthorizedException se a requisição for anônima ou não trouxer um JWT
     */
    private static Identity identityOf(SecurityIdentity securityIdentity) {
        if (securityIdentity == null || securityIdentity.isAnonymous()
                || !(securityIdentity.getPrincipal() instanceof JsonWebToken token)) {
            throw new UnauthorizedException(
                    "requisição sem token: mande Authorization: Bearer <token do Keycloak>");
        }

        String email = claim(token, "email");
        String preferredUsername = claim(token, "preferred_username");
        String name = claim(token, "name");

        return new Identity(
                AuthProvider.fromAlias(claim(token, IDENTITY_PROVIDER)),
                token.getSubject(),
                email != null ? email : preferredUsername,
                name != null && !name.isBlank() ? name : preferredUsername,
                // as roles de realm do Keycloak já chegam no SecurityIdentity: o quarkus-oidc lê
                // realm_access.roles sozinho, e não há prefixo ROLE_ para acrescentar nem conversor
                // à mão como a versão Spring precisava
                securityIdentity.hasRole(Role.AUTHOR.claim()));
    }

    private static String claim(JsonWebToken token, String name) {
        return Optional.<Object>ofNullable(token.getClaim(name)).map(Object::toString).orElse(null);
    }
}
