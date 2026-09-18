package dev.manuelantunes.axonposts.infrastructure.security;

import dev.manuelantunes.axonposts.domain.user.PasswordVerifier;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * O que sobrou de segurança escrita à mão depois da migração para o Keycloak: <b>uma linha</b>.
 *
 * <h2>O que não está aqui, e é o ponto</h2>
 * Não há emissão de token, verificação de assinatura, decoder, conversor de roles nem cadeia de filtros.
 * O {@code quarkus-oidc} descobre o JWKS a partir do {@code quarkus.oidc.auth-server-url}, valida
 * assinatura, {@code exp} e {@code iss}, e põe as roles de realm do Keycloak no {@code SecurityIdentity}.
 * <p>
 * A versão Spring precisava de um {@code SecurityConfig} com cadeia de filtros, um
 * {@code JwtAuthenticationConverter} e um {@code KeycloakRealmRolesConverter} de setenta linhas — este
 * último só porque o {@code JwtGrantedAuthoritiesConverter} de fábrica não lê claim aninhada e porque o
 * Spring exige o prefixo {@code ROLE_}. Nada disso tem equivalente aqui: some com a classe inteira.
 *
 * <h2>Por que o BCrypt continua</h2>
 * A coluna {@code accounts.password_hash} existe para as contas do provedor {@code CREDENTIAL}, que o
 * modelo do account linking prevê. Quem verifica a senha de quem entra pelo Keycloak é o Keycloak; isto
 * atende ao caso em que uma conta local tem senha própria.
 */
@ApplicationScoped
public class SecurityProducer {

    /**
     * A porta de domínio {@link PasswordVerifier} ligada ao BCrypt do Quarkus — uma linha, e o domínio
     * sem framework. Trocar por Argon2 é trocar esta expressão.
     */
    @Produces
    @Singleton
    public PasswordVerifier passwordVerifier() {
        return BcryptUtil::matches;
    }
}
