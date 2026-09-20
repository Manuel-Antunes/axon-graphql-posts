package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;
import dev.manuelantunes.axonposts.domain.user.vo.PasswordHash;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");
    private static final String SUBJECT = "kc-4b1f-author";
    private static final String HASH = "hash-irrelevante";

    /*
     * ESTE TESTE MONTA OS PRÓPRIOS OBJETOS, e não é descuido.
     *
     * É o `libs/users` testando a si mesmo: usar os fixtures compartilhados de `libs/test-support`
     * significaria depender de um módulo que depende DESTE, e o reator do Maven recusa — ele não
     * distingue escopo de teste ao detectar ciclo. O que sobra é melhor que o contorno: uma lib que
     * exercita as próprias invariantes pela própria API pública não precisa de ninguém para provar
     * que funciona.
     *
     * Publicador que DESCARTA: aqui se monta estado, não se verifica evento. Quem verifica os
     * eventos do usuário é o teste do command, com um duplo que grava de verdade.
     */
    private static final DomainEventPublisher DISCARDED = event -> {
    };

    private final PasswordVerifier verifier = String::equals;

    private final Account withPassword = User
            .register(UserId.newId(), "com-senha@example.com", "Com Senha", false, null, null,
                    CREATED_AT, DISCARDED)
            .link(AuthProvider.CREDENTIAL, "com-senha@example.com",
                    PasswordHash.of(HASH), CREATED_AT, DISCARDED);

    private final Account federated = author().accountFor(AuthProvider.KEYCLOAK).orElseThrow();

    /** Um autor com a conta do Keycloak ligada — como ele existiria depois do provisionamento. */
    private static User author() {
        User author = User.register(UserId.of("author-1"), "manuel@example.com", "Manuel Antunes",
                true, "Escrevendo sobre Axon.", null, CREATED_AT, DISCARDED);
        author.link(AuthProvider.KEYCLOAK, SUBJECT, CREATED_AT, DISCARDED);
        return author;
    }

    @Test
    void authenticatesWithTheRightPassword() {
        assertThat(withPassword.authenticates(HASH, verifier)).isTrue();
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
        assertThat(federated.identifies(AuthProvider.KEYCLOAK, SUBJECT)).isTrue();
        // mesmo subject, outro provedor: não é a mesma credencial
        assertThat(federated.identifies(AuthProvider.GOOGLE, SUBJECT)).isFalse();
        assertThat(federated.identifies(AuthProvider.KEYCLOAK, "outro-sub")).isFalse();
    }

    @Test
    void theUserNoLongerCarriesTheCredential() {
        // é a migração inteira em uma asserção: a responsabilidade saiu de User e foi para Account
        assertThat(author()).isNotInstanceOf(Authenticatable.class);
        assertThat(federated).isInstanceOf(Authenticatable.class);
    }
}
