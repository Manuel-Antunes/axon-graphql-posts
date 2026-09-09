package dev.manuelantunes.axonposts.exceptions;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.exception.NotThePostAuthorException;
import dev.manuelantunes.axonposts.domain.post.exception.PostAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.shared.AlreadyDeletedException;
import dev.manuelantunes.axonposts.domain.shared.NotDeletedException;
import dev.manuelantunes.axonposts.domain.tag.exception.InvalidTagException;
import dev.manuelantunes.axonposts.domain.tag.exception.TagAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.tag.exception.TagNotFoundException;
import dev.manuelantunes.axonposts.domain.user.exception.InvalidUserException;
import dev.manuelantunes.axonposts.domain.user.exception.AccountAlreadyLinkedException;
import dev.manuelantunes.axonposts.domain.user.exception.EmailAlreadyInUseException;
import dev.manuelantunes.axonposts.domain.user.exception.NotAnAuthorException;
import dev.manuelantunes.axonposts.domain.user.exception.UserNotFoundException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.axonframework.modelling.repository.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Traduz exceções do domínio, da validação e do Axon em erros GraphQL legíveis, em vez do genérico
 * "INTERNAL_ERROR".
 * <p>
 * Fica na camada de interface porque é aqui que se decide como uma falha aparece <b>no protocolo</b>: o
 * domínio lança {@code InvalidPostException} sem saber o que é um status HTTP ou um {@code ErrorType}.
 * <p>
 * Todas as portas de entrada de erro de input desembocam no mesmo {@code BAD_REQUEST}:
 * {@link ConstraintViolationException} (Bean Validation, na borda) e as invariantes dos dois domínios,
 * Post e Tag, mais fundo. O cliente não precisa saber qual delas o pegou.
 * <p>
 * Uma exceção lançada dentro de um command ou de uma query atravessa o {@code CompletableFuture} do
 * gateway e pode chegar aqui embrulhada ({@code CompletionException}, {@code CommandExecutionException}
 * etc.), então a classificação percorre a cadeia de causas.
 */
@ControllerAdvice
public class AppGraphQlExceptionHandler {

    @GraphQlExceptionHandler
    public GraphQLError handle(Throwable ex, DataFetchingEnvironment env) {
        // Uma violação de restrição do banco é decisão de domínio, não falha de infraestrutura: traduzida
        // primeiro, ela entra na mesma classificação que uma checagem na aplicação produziria.
        // Vem antes do laço porque a violação chega embrulhada no commit do Axon, longe de onde nasceu.
        Throwable classified = DataIntegrityTranslator.translate(ex).map(Throwable.class::cast).orElse(ex);

        for (Throwable t = classified; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violations) {
                return error(ErrorType.BAD_REQUEST, describe(violations), env);
            }
            if (t instanceof InvalidPostException || t instanceof PostAlreadyExistsException
                    || t instanceof InvalidTagException || t instanceof TagAlreadyExistsException
                    || t instanceof InvalidUserException
                    || t instanceof AccountAlreadyLinkedException || t instanceof EmailAlreadyInUseException
                    // as duas guardas do mixin SoftDeletable: apagar o apagado, restaurar o vivo
                    || t instanceof AlreadyDeletedException || t instanceof NotDeletedException) {
                return error(ErrorType.BAD_REQUEST, t.getMessage(), env);
            }
            // token ausente, expirado ou de outro emissor: tudo "identifique-se"
            if (t instanceof AuthenticationException) {
                return error(ErrorType.UNAUTHORIZED, "credenciais inválidas ou ausentes", env);
            }
            // autenticado, mas sem permissão. A mensagem não diz o que faltou, só que faltou
            if (t instanceof AccessDeniedException || t instanceof NotAnAuthorException
                    || t instanceof NotThePostAuthorException) {
                return error(ErrorType.FORBIDDEN, "sem permissão para esta operação", env);
            }
            if (t instanceof UserNotFoundException) {
                return error(ErrorType.NOT_FOUND, t.getMessage(), env);
            }
            if (t instanceof TagNotFoundException) {
                return error(ErrorType.NOT_FOUND, t.getMessage(), env);
            }
            if (t instanceof EntityNotFoundException) {
                return error(ErrorType.NOT_FOUND, "Post não encontrado", env);
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return error(ErrorType.INTERNAL_ERROR, "falha inesperada: " + rootCause(ex).getMessage(), env);
    }

    /**
     * Junta as violações numa mensagem estável: ordenadas por caminho, para a mesma requisição inválida
     * produzir sempre o mesmo texto (a {@code Set} do Bean Validation não tem ordem definida).
     */
    private static String describe(ConstraintViolationException ex) {
        return ex.getConstraintViolations().stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .map(ConstraintViolation::getMessage)
                .distinct()
                .collect(Collectors.joining("; "));
    }

    private static Throwable rootCause(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private static GraphQLError error(ErrorType type, String message, DataFetchingEnvironment env) {
        return GraphqlErrorBuilder.newError(env)
                .errorType(type)
                .message(message)
                .build();
    }
}
