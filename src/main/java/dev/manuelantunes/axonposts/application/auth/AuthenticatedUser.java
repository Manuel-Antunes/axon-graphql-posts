package dev.manuelantunes.axonposts.application.auth;

import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.User;
import reactor.core.publisher.Mono;

/**
 * Quem está autenticado nesta requisição, como entidade de domínio.
 *
 * <h2>Por que esta interface existe</h2>
 * Porque sem ela a camada de apresentação importava {@code infrastructure.security.CurrentUser} — um
 * controller conhecendo o mecanismo de autenticação. A seta apontava para o lado errado: {@code interfaces}
 * dependia de {@code infrastructure}, quando as duas deveriam depender de {@code application}.
 * <p>
 * O controller precisa saber <b>quem</b> está falando; não precisa saber que isso vem de um JWT, de um
 * {@code ReactiveSecurityContextHolder} ou de um {@code sub} do Keycloak. Tudo isso é resposta para "como",
 * e mora do outro lado desta porta.
 * <p>
 * O ganho concreto: trocar Keycloak por outro emissor, ou autenticar por mTLS num serviço interno, não
 * toca em nenhum controller. A implementação atual é {@code infrastructure.security.CurrentUser}.
 *
 * <h2>Erros</h2>
 * Requisição anônima falha o {@code Mono} com {@code AuthenticationCredentialsNotFoundException}; usuário
 * autenticado que não é autor, com {@code NotAnAuthorException}. Os dois viram erro GraphQL no
 * {@code AppGraphQlExceptionHandler}.
 */
public interface AuthenticatedUser {

    /** O usuário local correspondente ao token, provisionado na primeira vez que aparece. */
    Mono<User> require();

    /**
     * O mesmo usuário, <b>como autor</b>.
     * <p>
     * O {@code @PreAuthorize("hasRole('AUTHOR')")} do controller já barrou pela role do token; este método
     * confirma contra o tipo concreto do agregado, que é a verdade final.
     */
    Mono<Author> requireAuthor();
}
