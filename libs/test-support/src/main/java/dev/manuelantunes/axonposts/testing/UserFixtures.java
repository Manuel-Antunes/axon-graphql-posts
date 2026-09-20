package dev.manuelantunes.axonposts.testing;

import dev.manuelantunes.axonposts.domain.user.Account;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.vo.PasswordHash;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;

import java.time.Instant;

public final class UserFixtures {
    public static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");

    public static final UserId AUTHOR_ID = UserId.of("author-1");
    public static final String AUTHOR_NAME = "Manuel Antunes";
    public static final String AUTHOR_EMAIL = "manuel@example.com";

    public static final UserId READER_ID = UserId.of("reader-1");
    public static final String READER_EMAIL = "leitor@example.com";

    public static final String AUTHOR_SUBJECT = "kc-4b1f-author";
    public static final String READER_SUBJECT = "kc-9c2a-reader";

    public static final String FAKE_HASH = "hash-irrelevante";

    private static final DomainEventPublisher DISCARDED = event -> {
    };

    private UserFixtures() {
    }

    public static Author author() {
        User author = User.register(AUTHOR_ID, AUTHOR_EMAIL, AUTHOR_NAME, true,
                "Escrevendo sobre Axon.", null, CREATED_AT, DISCARDED);
        author.link(AuthProvider.KEYCLOAK, AUTHOR_SUBJECT, CREATED_AT, DISCARDED);
        return (Author) author;
    }

    public static User reader() {
        User reader = User.register(READER_ID, READER_EMAIL, "Leitor Anônimo", false, null, null,
                CREATED_AT, DISCARDED);
        reader.link(AuthProvider.KEYCLOAK, READER_SUBJECT, CREATED_AT, DISCARDED);
        return reader;
    }

    public static Account credentialAccount() {
        return User.register(UserId.newId(), "com-senha@example.com", "Com Senha", false, null, null,
                        CREATED_AT, DISCARDED)
                .link(AuthProvider.CREDENTIAL, "com-senha@example.com",
                        PasswordHash.of(FAKE_HASH), CREATED_AT, DISCARDED);
    }

    public static Account federatedAccount() {
        return author().accountFor(AuthProvider.KEYCLOAK).orElseThrow();
    }
}
