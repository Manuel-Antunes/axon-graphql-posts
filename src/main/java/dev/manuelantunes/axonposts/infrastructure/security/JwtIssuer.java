package dev.manuelantunes.axonposts.infrastructure.security;

import dev.manuelantunes.axonposts.domain.user.Role;
import dev.manuelantunes.axonposts.domain.user.User;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Emite o JWT de um usuário autenticado.
 *
 * <h2>As roles saem do tipo, não de uma coluna</h2>
 * {@code user.roles()} é polimórfico: {@code User} devolve {@code [USER]} e {@code Author} sobrescreve
 * para {@code [USER, AUTHOR]}. Ou seja, o token afirma "sou autor" <b>porque</b> a linha veio da tabela
 * {@code authors} e o Hibernate instanciou um {@code Author} — não porque alguém marcou uma flag.
 * <p>
 * É o que fecha o ciclo: o {@code @PreAuthorize("hasRole('AUTHOR')")} confia nessa claim para barrar
 * cedo e barato, e o {@code CreatePostCommand} reconfirma contra o banco com um {@code instanceof} antes
 * de escrever. A checagem rápida e a lenta partem da mesma fonte.
 */
@Component
public class JwtIssuer {

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtIssuer(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    /** O token e o instante em que ele expira. */
    public record IssuedToken(String value, Instant expiresAt) {
    }

    public IssuedToken issue(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.expiration());

        List<String> authorities = user.roles().stream().map(Role::authority).toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                // o sub é o UserId: é por ele que o CurrentUser recarrega a entidade
                .subject(user.id().value())
                .claim("name", user.name().value())
                .claim(SecurityConfig.ROLES_CLAIM, authorities)
                .build();

        String token = encoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        return new IssuedToken(token, expiresAt);
    }
}
