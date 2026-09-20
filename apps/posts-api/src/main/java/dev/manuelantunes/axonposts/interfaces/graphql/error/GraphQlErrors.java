package dev.manuelantunes.axonposts.interfaces.graphql.error;

import java.util.Comparator;
import java.util.stream.Collectors;

import org.eclipse.microprofile.graphql.GraphQLException;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.exception.NotThePostAuthorException;
import dev.manuelantunes.axonposts.domain.post.exception.PostAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.shared.AlreadyDeletedException;
import dev.manuelantunes.axonposts.domain.shared.NotDeletedException;
import dev.manuelantunes.axonposts.domain.tag.exception.InvalidTagException;
import dev.manuelantunes.axonposts.domain.tag.exception.TagAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.tag.exception.TagNotFoundException;
import dev.manuelantunes.axonposts.domain.user.exception.AccountAlreadyLinkedException;
import dev.manuelantunes.axonposts.domain.user.exception.EmailAlreadyInUseException;
import dev.manuelantunes.axonposts.domain.user.exception.InvalidUserException;
import dev.manuelantunes.axonposts.domain.user.exception.NotAnAuthorException;
import dev.manuelantunes.axonposts.domain.user.exception.UserNotFoundException;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

public final class GraphQlErrors {
    private GraphQlErrors() {
    }

    public static <T> Uni<T> translating(Uni<T> uni) {
        return uni.onFailure().transform(GraphQlErrors::translate);
    }

    public static <T> Multi<T> translating(Multi<T> multi) {
        return multi.onFailure().transform(GraphQlErrors::translate);
    }

    public static Throwable translate(Throwable thrown) {
        Throwable classified = DataIntegrityTranslator.translate(thrown)
                .map(Throwable.class::cast)
                .orElse(thrown);

        for (Throwable t = classified; t != null; t = t.getCause()) {
            if (t instanceof ApplicationGraphQlException || t instanceof GraphQLException) {
                return t;
            }
            if (t instanceof ConstraintViolationException violations) {
                return new BadRequestException(describe(violations));
            }
            if (t instanceof InvalidPostException || t instanceof PostAlreadyExistsException
                    || t instanceof InvalidTagException || t instanceof TagAlreadyExistsException
                    || t instanceof InvalidUserException
                    || t instanceof AccountAlreadyLinkedException || t instanceof EmailAlreadyInUseException
                    || t instanceof AlreadyDeletedException || t instanceof NotDeletedException) {
                return new BadRequestException(t.getMessage());
            }
            if (t instanceof io.quarkus.security.UnauthorizedException
                    || t instanceof io.quarkus.security.AuthenticationFailedException) {
                return new UnauthorizedException("credenciais inválidas ou ausentes");
            }
            if (t instanceof io.quarkus.security.ForbiddenException
                    || t instanceof NotAnAuthorException
                    || t instanceof NotThePostAuthorException) {
                return new ForbiddenException("sem permissão para esta operação");
            }
            if (t instanceof UserNotFoundException || t instanceof TagNotFoundException) {
                return new NotFoundException(t.getMessage());
            }
            if (t instanceof org.axonframework.modelling.repository.EntityNotFoundException) {
                return new NotFoundException("Post não encontrado");
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return thrown;
    }

    private static String describe(ConstraintViolationException ex) {
        return ex.getConstraintViolations().stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .map(ConstraintViolation::getMessage)
                .distinct()
                .collect(Collectors.joining("; "));
    }
}
