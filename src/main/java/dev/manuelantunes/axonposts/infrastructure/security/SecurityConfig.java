package dev.manuelantunes.axonposts.infrastructure.security;

import dev.manuelantunes.axonposts.domain.user.PasswordVerifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Segurança reativa: valida o JWT que chega e emite o que sai.
 *
 * <h2>Por que {@code permitAll} na cadeia e {@code @PreAuthorize} nos métodos</h2>
 * Um endpoint GraphQL é <b>um</b> caminho HTTP para todas as operações. Autorizar por rota decidiria o
 * mesmo para {@code login}, {@code posts} e {@code createPost}, que precisam de coisas diferentes — e
 * bloquearia até a introspecção do schema. Então a cadeia só <i>autentica</i> (monta o
 * {@code SecurityContext} a partir do {@code Authorization: Bearer}) e quem <i>autoriza</i> é o método
 * do controller, que é onde a operação finalmente tem nome.
 * <p>
 * Uma requisição sem token não é rejeitada aqui: ela segue anônima e só esbarra no {@code @PreAuthorize}
 * se pedir algo que exija identidade. É o que permite ler {@code posts} deslogado.
 *
 * <h2>{@code @EnableReactiveMethodSecurity}</h2>
 * A variante reativa: o {@code @PreAuthorize} passa a valer para métodos que devolvem {@code Mono}/
 * {@code Flux}, tirando o {@code SecurityContext} do contexto do Reactor em vez de um {@code ThreadLocal}.
 * É o que funciona aqui — o contexto sobrevive ao {@code subscribeOn(boundedElastic)} das mutations, que
 * um ThreadLocal não sobreviveria.
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /** Claim onde as roles viajam. Não é padrão do JWT, então emissor e conversor têm de concordar. */
    public static final String ROLES_CLAIM = "roles";

    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key));
    }

    /**
     * Valida assinatura, {@code exp} e {@code iss}. O validador de emissor é explícito: sem ele, um token
     * assinado com a mesma chave por outra aplicação seria aceito.
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(SecretKey key, JwtProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /**
     * Claim {@code roles} → authorities, <b>sem prefixo acrescentado</b>: o token já traz
     * {@code ROLE_AUTHOR} pronto, escrito por {@code Role.authority()}. Deixar o conversor prefixar de
     * novo daria {@code ROLE_ROLE_AUTHOR} e o {@code hasRole('AUTHOR')} nunca casaria.
     */
    @Bean
    Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        // o principal name vira o "sub", que é o UserId — é dele que o CurrentUser parte
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

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * A porta de domínio {@link PasswordVerifier} ligada ao encoder do Spring. Uma linha, e é ela que
     * mantém {@code User.authenticates(...)} sem nenhum import de framework.
     */
    @Bean
    PasswordVerifier passwordVerifier(PasswordEncoder encoder) {
        return encoder::matches;
    }
}
