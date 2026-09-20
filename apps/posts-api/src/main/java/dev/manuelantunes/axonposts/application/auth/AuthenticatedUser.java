package dev.manuelantunes.axonposts.application.auth;

import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.User;
import io.smallrye.mutiny.Uni;

public interface AuthenticatedUser {
    Uni<User> require();

    Uni<Author> requireAuthor();
}
