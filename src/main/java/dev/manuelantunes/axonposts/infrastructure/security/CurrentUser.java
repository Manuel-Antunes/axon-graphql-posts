package dev.manuelantunes.axonposts.infrastructure.security;

import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.exception.NotAnAuthorException;
import dev.manuelantunes.axonposts.domain.user.exception.UserNotFoundException;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Quem está logado, como entidade de domínio.
 *
 * <h2>Do token para a entidade</h2>
 * O {@code SecurityContext} reativo entrega o {@code sub} do JWT — um id, e nada mais. Esta classe o
 * troca pela entidade concreta do banco, que é onde mora a verdade sobre o tipo: um id de autor volta
 * como {@code Author} porque existe linha em {@code authors}, não porque o token disse que sim.
 * <p>
 * Vale a ida ao banco: o token pode ter sido emitido antes de o papel ser revogado, e a versão barata da
 * verificação ({@code hasRole}) é justamente a que não sabe disso.
 *
 * <h2>Por que {@code ReactiveSecurityContextHolder}</h2>
 * Em WebFlux o {@code SecurityContext} vive no contexto do Reactor, não num {@code ThreadLocal} — é o
 * que faz ele sobreviver ao {@code subscribeOn(boundedElastic())} das mutations. O Spring GraphQL
 * propaga esse contexto para dentro dos data fetchers, então isto funciona igual num
 * {@code @MutationMapping} e num {@code @QueryMapping}.
 */
@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    /** O id de quem está autenticado; erro se a requisição for anônima. */
    public Mono<UserId> id() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(authentication -> UserId.of(authentication.getName()))
                .switchIfEmpty(Mono.error(() -> new AuthenticationCredentialsNotFoundException(
                        "requisição sem token: mande Authorization: Bearer <token>")));
    }

    /** O usuário autenticado, no tipo concreto dele. */
    public Mono<User> require() {
        return id().flatMap(userId -> Mono
                .fromCallable(() -> users.findById(userId).orElseThrow(() -> new UserNotFoundException(userId)))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * O usuário autenticado <b>como autor</b>.
     * <p>
     * Este é o upcast que o {@code @PreAuthorize("hasRole('AUTHOR')")} do controller torna seguro: quando
     * o método chega a rodar, a claim já garantiu o papel, e o {@code instanceof} só confirma contra o
     * banco. O {@code else} existe para quando as duas discordam — e aí quem manda é a tabela.
     */
    public Mono<Author> requireAuthor() {
        return require().flatMap(user -> user instanceof Author author
                ? Mono.just(author)
                : Mono.error(new NotAnAuthorException(user.id())));
    }
}
