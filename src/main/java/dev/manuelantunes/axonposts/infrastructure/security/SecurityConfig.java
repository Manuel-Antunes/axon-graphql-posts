package dev.manuelantunes.axonposts.infrastructure.security;

import dev.manuelantunes.axonposts.domain.user.PasswordVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

/**
 * Segurança reativa depois da migração: a aplicação é <b>só um resource server</b>.
 *
 * <h2>O que sumiu, e é o ponto da migração</h2>
 * Não há mais emissão de token, verificação de senha, mutation de {@code login} nem segredo de assinatura
 * no {@code application.yml}. Sobrou <i>validar</i> o que o Keycloak emitiu.
 * <p>
 * Some com isso uma classe inteira de risco que era nossa e passou a ser dele: rotação de chave,
 * bloqueio por tentativa, expiração, revogação, MFA, reset de senha. Nenhuma dessas linhas existe neste
 * projeto, e é assim que tem de ser.
 *
 * <h2>O decoder some do código também</h2>
 * Basta {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} apontar para o realm: o Boot busca
 * o {@code /.well-known/openid-configuration}, descobre o JWKS e monta o {@code ReactiveJwtDecoder} com
 * validação de assinatura, {@code exp} e {@code iss}. Antes isso era um {@code @Bean} escrito à mão
 * porque a chave era simétrica e nossa.
 * <p>
 * A troca vale a pena por outro motivo: com JWKS a rotação de chave no Keycloak é transparente. Com o
 * segredo compartilhado, era um deploy.
 *
 * <h2>{@code permitAll} na cadeia, {@code @PreAuthorize} nos métodos</h2>
 * Continua igual, e pelo mesmo motivo de sempre: um endpoint GraphQL é um caminho HTTP para todas as
 * operações. Autorizar por rota decidiria o mesmo para {@code posts} e {@code createPost}, e bloquearia
 * a introspecção. A cadeia só autentica; quem autoriza é o método, onde a operação tem nome.
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    /**
     * O conversor completo: roles de realm viram authorities, e o principal name vira o {@code sub} —
     * que é por onde o {@code CurrentUser} acha a {@code Account}.
     */
    @Bean
    Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRolesConverter());
        converter.setPrincipalClaimName(JwtClaimNames.SUB);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    @Bean
    SecurityWebFilterChain securityFilterChain(
            ServerHttpSecurity http,
            Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>> converter) {

        return http
                // API sem sessão e sem formulário: não há cookie para um CSRF proteger
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    /**
     * O encoder continua aqui, e não é sobra: a coluna {@code accounts.password_hash} existe para as
     * contas do provedor {@code CREDENTIAL}, que o modelo do account linking prevê. Quem verifica a senha
     * de quem entra pelo Keycloak é o Keycloak; isto atende ao caso em que uma conta local tem senha
     * própria.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** A porta de domínio {@link PasswordVerifier} ligada ao encoder — uma linha, e o domínio sem framework. */
    @Bean
    PasswordVerifier passwordVerifier(PasswordEncoder encoder) {
        return encoder::matches;
    }
}
