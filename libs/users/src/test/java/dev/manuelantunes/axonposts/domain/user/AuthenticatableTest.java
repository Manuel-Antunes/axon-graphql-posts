package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;
import dev.manuelantunes.axonposts.domain.user.vo.PasswordHash;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatableTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");
    private static final String SUBJECT = "kc-4b1f-author";
    private static final String HASH = "hash-irrelevante";

    private static final DomainEventPublisher DISCARDED = event -> {
    };

    private final PasswordVerifier verifier = String::equals;

    private final Account withPassword = User
            .register(UserId.newId(), "com-senha@example.com", "Com Senha", false, null, null,
                    CREATED_AT, DISCARDED)
            .link(AuthProvider.CREDENTIAL, "com-senha@example.com",
                    PasswordHash.of(HASH), CREATED_AT, DISCARDED);

    private final Account federated = author().accountFor(AuthProvider.KEYCLOAK).orElseThrow();

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
        assertThat(withPassword.authenticates(null, verifier)).isFalse();
        assertThat(withPassword.authenticates("", verifier)).isFalse();
        assertThat(withPassword.authenticates("   ", verifier)).isFalse();
    }

    @Test
    void aFederatedAccountHasNoPasswordAndNeverAuthenticatesLocally() {
        assertThat(federated.hasPassword()).isFalse();
        assertThat(federated.isFederated()).isTrue();

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
        assertThat(federated.identifies(AuthProvider.GOOGLE, SUBJECT)).isFalse();
        assertThat(federated.identifies(AuthProvider.KEYCLOAK, "outro-sub")).isFalse();
    }

    @Test
    void theUserNoLongerCarriesTheCredential() {
        assertThat(author()).isNotInstanceOf(Authenticatable.class);
        assertThat(federated).isInstanceOf(Authenticatable.class);
    }
}
