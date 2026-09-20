package dev.manuelantunes.axonposts.interfaces.graphql.error;

import dev.manuelantunes.axonposts.domain.tag.exception.TagAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.user.exception.AccountAlreadyLinkedException;
import dev.manuelantunes.axonposts.domain.user.exception.EmailAlreadyInUseException;
import dev.manuelantunes.axonposts.domain.user.exception.NotAnAuthorException;
import dev.manuelantunes.axonposts.domain.user.Author;
import jakarta.persistence.EntityNotFoundException;
import org.hibernate.exception.ConstraintViolationException;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public final class DataIntegrityTranslator {
    private static final Map<String, Supplier<RuntimeException>> BY_CONSTRAINT = Map.of(
            "fk_posts_author", NotAnAuthorException::new,
            "uk_accounts_provider_subject", AccountAlreadyLinkedException::new,
            "uk_users_email_active", EmailAlreadyInUseException::new,
            "uk_tags_name", TagAlreadyExistsException::new
    );

    private DataIntegrityTranslator() {
    }

    public static Optional<RuntimeException> translate(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation) {
                RuntimeException translated = translated(violation);
                if (translated != null) {
                    return Optional.of(translated);
                }
            }
            if (t instanceof EntityNotFoundException missing && namesAuthor(missing)) {
                return Optional.of(new NotAnAuthorException());
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return Optional.empty();
    }

    private static boolean namesAuthor(EntityNotFoundException missing) {
        String message = missing.getMessage();
        return message != null && message.contains(Author.class.getName());
    }

    private static RuntimeException translated(ConstraintViolationException violation) {
        String name = violation.getConstraintName();
        if (name == null) {
            return null;
        }
        Supplier<RuntimeException> mapped = BY_CONSTRAINT.get(name.toLowerCase());
        return mapped == null ? null : mapped.get();
    }

    public static java.util.Set<String> knownConstraints() {
        return BY_CONSTRAINT.keySet();
    }
}
