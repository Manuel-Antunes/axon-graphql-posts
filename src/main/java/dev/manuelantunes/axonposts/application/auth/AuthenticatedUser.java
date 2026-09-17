package dev.manuelantunes.axonposts.application.auth;

import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.User;
import io.smallrye.mutiny.Uni;

/**
 * Quem está autenticado nesta requisição, como entidade de domínio.
 *
 * <h2>Por que esta interface existe</h2>
 * Porque sem ela a camada de apresentação importava {@code infrastructure.security.CurrentUser} — um
 * resolver conhecendo o mecanismo de autenticação. A seta apontava para o lado errado:
 * {@code interfaces} dependia de {@code infrastructure}, quando as duas deveriam depender de
 * {@code application}.
 * <p>
 * O resolver precisa saber <b>quem</b> está falando; não precisa saber que isso vem de um JWT, de um
 * {@code SecurityIdentity} ou de um {@code sub} do Keycloak. Tudo isso é resposta para "como", e mora do
 * outro lado desta porta.
 * <p>
 * O ganho concreto: trocar Keycloak por outro emissor, ou autenticar por mTLS num serviço interno, não
 * toca em nenhum resolver. A implementação atual é {@code infrastructure.security.CurrentUser}.
 *
 * <h2>{@link Uni}, e não {@code Mono}</h2>
 * É a única diferença de assinatura em relação à versão Spring, e ela não é cosmética: o provisionamento
 * lê e escreve no Postgres com JPA bloqueante, então o trabalho tem de sair do event-loop do Vert.x. O
 * {@code Uni} devolvido por {@link #require()} já vem com {@code runSubscriptionOn(worker pool)} — quem
 * chama compõe e não precisa lembrar disso.
 *
 * <h2>Erros</h2>
 * Requisição anônima falha o {@code Uni} com {@code UnauthenticatedException}; usuário autenticado que
 * não é autor, com {@code NotAnAuthorException}. Os dois viram erro GraphQL em {@code GraphQlErrors}.
 */
public interface AuthenticatedUser {

    /** O usuário local correspondente ao token, provisionado na primeira vez que aparece. */
    Uni<User> require();

    /**
     * O mesmo usuário, <b>como autor</b>.
     * <p>
     * O {@code @RolesAllowed("author")} do resolver já barrou pela role do token; este método confirma
     * contra o tipo concreto do agregado, que é a verdade final.
     */
    Uni<Author> requireAuthor();
}
