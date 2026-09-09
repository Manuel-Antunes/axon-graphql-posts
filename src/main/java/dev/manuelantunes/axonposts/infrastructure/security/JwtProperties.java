package dev.manuelantunes.axonposts.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuração do JWT. O segredo é HS256 (assinatura simétrica): a mesma chave assina e verifica.
 * <p>
 * Simétrico serve porque quem emite e quem valida são <b>o mesmo processo</b>. Se o token passasse a ser
 * verificado por outro serviço, isto teria de virar RS256 — chave privada só em quem emite, pública em
 * quem valida — e só esta classe e as duas fábricas mudariam.
 *
 * @param secret     segredo HS256; precisa de pelo menos 32 bytes, senão o Nimbus recusa a chave
 * @param issuer     claim {@code iss}
 * @param expiration validade do token a partir da emissão
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, String issuer, Duration expiration) {

    /** Mínimo do HS256: 256 bits. */
    public static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes().length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret precisa de pelo menos " + MIN_SECRET_BYTES + " bytes para HS256");
        }
        issuer = issuer == null || issuer.isBlank() ? "axon-graphql-posts" : issuer;
        expiration = expiration == null ? Duration.ofHours(12) : expiration;
    }
}
