package dev.manuelantunes.axonposts.support;

import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;

import java.time.Instant;

/**
 * O autor e o leitor usados pelos testes. Ids fixos, para que a asserção sobre um evento possa ser
 * escrita literalmente em vez de depender do que um {@code UUID.randomUUID()} sorteou.
 */
public final class UserFixtures {

    public static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");

    public static final UserId AUTHOR_ID = UserId.of("author-1");
    public static final String AUTHOR_NAME = "Manuel Antunes";

    public static final UserId READER_ID = UserId.of("reader-1");

    private UserFixtures() {
    }

    /** O autor completo, como o banco devolveria. */
    public static Author author() {
        return Author.register(AUTHOR_ID, "manuel@example.com", AUTHOR_NAME, "hash-irrelevante",
                "Escrevendo sobre Axon.", CREATED_AT);
    }

    /** Um usuário que não é autor — o caso que o {@code CreatePostCommand} tem de recusar. */
    public static User reader() {
        return User.register(READER_ID, "leitor@example.com", "Leitor Anônimo", "hash-irrelevante", CREATED_AT);
    }
}
