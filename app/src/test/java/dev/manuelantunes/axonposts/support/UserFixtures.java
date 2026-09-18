package dev.manuelantunes.axonposts.support;

import dev.manuelantunes.axonposts.domain.user.Account;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.vo.PasswordHash;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;

import java.time.Instant;

/**
 * O autor e o leitor usados pelos testes, cada um com a credencial do Keycloak já ligada — que é como
 * eles existiriam depois do provisionamento.
 * <p>
 * Ids fixos, para que a asserção sobre um evento possa ser escrita literalmente em vez de depender do que
 * um {@code UUID.randomUUID()} sorteou.
 */
public final class UserFixtures {

    public static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");

    public static final UserId AUTHOR_ID = UserId.of("author-1");
    public static final String AUTHOR_NAME = "Manuel Antunes";
    public static final String AUTHOR_EMAIL = "manuel@example.com";

    public static final UserId READER_ID = UserId.of("reader-1");
    public static final String READER_EMAIL = "leitor@example.com";

    /** O {@code sub} que o Keycloak emitiria — outro espaço de identidade, de propósito. */
    public static final String AUTHOR_SUBJECT = "kc-4b1f-author";
    public static final String READER_SUBJECT = "kc-9c2a-reader";

    /** A senha "em hash" das contas CREDENTIAL dos testes; ver {@code AuthenticatableTest}. */
    public static final String FAKE_HASH = "hash-irrelevante";

    /**
     * Publicador que descarta: estas fixtures montam <b>estado</b>, não verificam eventos.
     * <p>
     * Desde que {@code User} virou agregado event-sourced, nascer é disparar um evento — e os testes que
     * só precisam de um autor pronto não deveriam ter de afirmar nada sobre isso. Quem verifica os
     * eventos do usuário é o teste do command, com um {@link RecordingDomainEvents} de verdade.
     */
    private static final DomainEventPublisher DISCARDED = event -> {
    };

    private UserFixtures() {
    }

    /** O autor, com a conta do Keycloak ligada. */
    public static Author author() {
        User author = User.register(AUTHOR_ID, AUTHOR_EMAIL, AUTHOR_NAME, true,
                "Escrevendo sobre Axon.", null, CREATED_AT, DISCARDED);
        author.link(AuthProvider.KEYCLOAK, AUTHOR_SUBJECT, CREATED_AT, DISCARDED);
        // o @EntityCreator resolveu o tipo pelo evento; o cast só torna isso visível na assinatura
        return (Author) author;
    }

    /** Um usuário que não é autor — o caso que o {@code CreatePostCommand} tem de recusar. */
    public static User reader() {
        User reader = User.register(READER_ID, READER_EMAIL, "Leitor Anônimo", false, null, null,
                CREATED_AT, DISCARDED);
        reader.link(AuthProvider.KEYCLOAK, READER_SUBJECT, CREATED_AT, DISCARDED);
        return reader;
    }

    /** Uma conta com senha local: o outro lado do "algumas contas têm senha e outras não". */
    public static Account credentialAccount() {
        return User.register(UserId.newId(), "com-senha@example.com", "Com Senha", false, null, null,
                        CREATED_AT, DISCARDED)
                .link(AuthProvider.CREDENTIAL, "com-senha@example.com",
                        PasswordHash.of(FAKE_HASH), CREATED_AT, DISCARDED);
    }

    /** Uma conta federada: sem senha, por definição. */
    public static Account federatedAccount() {
        return author().accountFor(AuthProvider.KEYCLOAK).orElseThrow();
    }
}
