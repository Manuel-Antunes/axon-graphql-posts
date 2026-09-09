package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.support.UserFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O mixin {@link Authenticatable} através de quem o implementa.
 * <p>
 * O {@link PasswordVerifier} aqui é um lambda de mentira ({@code raw.equals(hash)}), e é justamente esse
 * o ponto do mixin: as <b>regras</b> de autenticação (senha vazia nunca passa, a comparação é com o hash
 * guardado) são testáveis sem BCrypt nenhum, porque o algoritmo é parâmetro.
 */
class AuthenticatableTest {

    /** Verificador trivial: o "hash" de {@code x} é o próprio {@code x}. */
    private final PasswordVerifier verifier = String::equals;

    private final Author author = UserFixtures.author(); // passwordHash = "hash-irrelevante"

    @Test
    void authenticatesWithTheRightPassword() {
        assertThat(author.authenticates("hash-irrelevante", verifier)).isTrue();
    }

    @Test
    void rejectsTheWrongPassword() {
        assertThat(author.authenticates("outra-coisa", verifier)).isFalse();
    }

    @Test
    void neverAuthenticatesOnBlankOrNull() {
        // a guarda vem antes do verifier: um encoder real lançaria com null e gastaria um BCrypt com ""
        assertThat(author.authenticates(null, verifier)).isFalse();
        assertThat(author.authenticates("", verifier)).isFalse();
        assertThat(author.authenticates("   ", verifier)).isFalse();
    }

    @Test
    void identifiedByComparesTheNormalizedEmail() {
        // Email normaliza para minúsculas no construtor — é o que faz esta comparação exata bastar
        assertThat(author.identifiedBy(Email.of("  MANUEL@example.COM  "))).isTrue();
        assertThat(author.identifiedBy(Email.of("outro@example.com"))).isFalse();
        assertThat(author.identifiedBy(null)).isFalse();
    }

    @Test
    void theMixinReachesTheSubclassWithoutASingleLineInIt() {
        // Author não declara nada de autenticação: herda de User, que só implementa os dois acessores
        assertThat(author).isInstanceOf(Authenticatable.class);
        assertThat(UserFixtures.reader()).isInstanceOf(Authenticatable.class);
        // e o mesmo vale para o soft delete
        assertThat(author).isInstanceOf(dev.manuelantunes.axonposts.domain.shared.SoftDeletable.class);
    }
}
