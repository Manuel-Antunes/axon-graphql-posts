package dev.manuelantunes.axonposts.support;

import dev.manuelantunes.axonposts.domain.user.Account;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.vo.PasswordHash;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;

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

    private UserFixtures() {
    }

    /** O autor, com a conta do Keycloak ligada. */
    public static Author author() {
        Author author = Author.register(AUTHOR_ID, AUTHOR_EMAIL, AUTHOR_NAME,
                "Escrevendo sobre Axon.", CREATED_AT);
        author.link(AuthProvider.KEYCLOAK, AUTHOR_SUBJECT, CREATED_AT);
        return author;
    }

    /** Um usuário que não é autor — o caso que o {@code CreatePostCommand} tem de recusar. */
    public static User reader() {
        User reader = User.register(READER_ID, READER_EMAIL, "Leitor Anônimo", CREATED_AT);
        reader.link(AuthProvider.KEYCLOAK, READER_SUBJECT, CREATED_AT);
        return reader;
    }

    /** Uma conta com senha local: o outro lado do "algumas contas têm senha e outras não". */
    public static Account credentialAccount() {
        return User.register(UserId.newId(), "com-senha@example.com", "Com Senha", CREATED_AT)
                .link(AuthProvider.CREDENTIAL, "com-senha@example.com", PasswordHash.of(FAKE_HASH), CREATED_AT);
    }

    /** Uma conta federada: sem senha, por definição. */
    public static Account federatedAccount() {
        return author().accountFor(AuthProvider.KEYCLOAK).orElseThrow();
    }
}
