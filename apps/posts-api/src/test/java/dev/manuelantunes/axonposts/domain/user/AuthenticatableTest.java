package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.support.UserFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O mixin {@link Authenticatable} através de quem o implementa — que agora é a {@link Account}, e não
 * mais o {@code User}.
 *
 * <h2>O que este arquivo prova sobre a migração</h2>
 * Os casos são <b>os mesmos</b> de antes do Keycloak: senha certa passa, senha errada não, vazio nunca.
 * Só mudou de quem se pergunta. Um mixin que tivesse virado herança ou código dentro de {@code User}
 * teria obrigado a reescrever a regra junto com a tabela.
 * <p>
 * O {@link PasswordVerifier} é um lambda de mentira ({@code raw.equals(hash)}): as regras são testáveis
 * sem BCrypt porque o algoritmo é parâmetro.
 */
class AuthenticatableTest {

    private final PasswordVerifier verifier = String::equals;

    private final Account withPassword = UserFixtures.credentialAccount();
    private final Account federated = UserFixtures.federatedAccount();

    @Test
    void authenticatesWithTheRightPassword() {
        assertThat(withPassword.authenticates(UserFixtures.FAKE_HASH, verifier)).isTrue();
    }

    @Test
    void rejectsTheWrongPassword() {
        assertThat(withPassword.authenticates("outra-coisa", verifier)).isFalse();
    }

    @Test
    void neverAuthenticatesOnBlankOrNull() {
        // a guarda vem antes do verifier: um encoder real lançaria com null e gastaria um BCrypt com ""
        assertThat(withPassword.authenticates(null, verifier)).isFalse();
        assertThat(withPassword.authenticates("", verifier)).isFalse();
        assertThat(withPassword.authenticates("   ", verifier)).isFalse();
    }

    @Test
    void aFederatedAccountHasNoPasswordAndNeverAuthenticatesLocally() {
        // o caso central do account linking: algumas contas têm senha, outras não
        assertThat(federated.hasPassword()).isFalse();
        assertThat(federated.isFederated()).isTrue();

        // e não é que a senha esteja errada — é que não há o que comparar
        assertThat(federated.authenticates("qualquer-coisa", verifier)).isFalse();
    }

    @Test
    void aCredentialAccountIsNotFederated() {
        assertThat(withPassword.hasPassword()).isTrue();
        assertThat(withPassword.isFederated()).isFalse();
    }

    @Test
    void identifiesMatchesOnProviderAndSubjectTogether() {
        assertThat(federated.identifies(AuthProvider.KEYCLOAK, UserFixtures.AUTHOR_SUBJECT)).isTrue();
        // mesmo subject, outro provedor: não é a mesma credencial
        assertThat(federated.identifies(AuthProvider.GOOGLE, UserFixtures.AUTHOR_SUBJECT)).isFalse();
        assertThat(federated.identifies(AuthProvider.KEYCLOAK, "outro-sub")).isFalse();
    }

    @Test
    void theUserNoLongerCarriesTheCredential() {
        // é a migração inteira em uma asserção: a responsabilidade saiu de User e foi para Account
        assertThat(UserFixtures.author()).isNotInstanceOf(Authenticatable.class);
        assertThat(federated).isInstanceOf(Authenticatable.class);
    }
}
