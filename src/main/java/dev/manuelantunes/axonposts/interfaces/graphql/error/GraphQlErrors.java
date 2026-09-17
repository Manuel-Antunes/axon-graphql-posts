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

/**
 * Traduz exceções do domínio, da validação e do Axon em erros GraphQL legíveis, em vez do genérico
 * "System Error" que o SmallRye devolve para qualquer exceção não-checada.
 * <p>
 * Fica na camada de interface porque é aqui que se decide como uma falha aparece <b>no protocolo</b>: o
 * domínio lança {@code InvalidPostException} sem saber o que é um status HTTP ou um código de erro.
 *
 * <h2>Quem chama isto</h2>
 * Um interceptador CDI, {@code ErrorTranslationInterceptor}, ligado por {@link TranslatesErrors} na classe
 * de cada {@code @GraphQLApi}. É o equivalente do {@code @GraphQlExceptionHandler} do Spring montado com a
 * peça que o Quarkus tem: o SmallRye não instancia o resolver, ele o pede ao CDI, e o que volta é um
 * client proxy — então a chamada entra pela cadeia de interceptadores como qualquer outra. É a mesma razão
 * pela qual {@code @RolesAllowed} e {@code @Valid} já funcionavam nos resolvers.
 * <p>
 * O SmallRye em si não tem hook para isto: o {@code EventingService} dele <i>observa</i> o erro, mas não o
 * substitui.
 * <p>
 * O que se ganhou em relação a uma chamada por resolver: o caminho <b>síncrono</b> passa a ser traduzido.
 * Um {@code @Valid} que estoura, ou um cursor recusado antes de o {@code Uni} existir, nunca chegava a
 * {@link #translating(Uni)} — ficava fora da classificação, sem {@code extensions.code}. Agora os dois
 * caminhos convergem aqui.
 *
 * <h2>A cadeia de causas</h2>
 * Uma exceção lançada dentro de um command ou de uma query atravessa o {@code CompletableFuture} do
 * gateway e chega aqui embrulhada ({@code CompletionException}, {@code CommandExecutionException}, o
 * commit do {@code ProcessingContext}...), então a classificação <b>percorre a cadeia</b> em vez de olhar
 * só o topo.
 */
public final class GraphQlErrors {

    private GraphQlErrors() {
    }

    /** O operador que cada resolver aplica ao seu {@link Uni}. */
    public static <T> Uni<T> translating(Uni<T> uni) {
        return uni.onFailure().transform(GraphQlErrors::translate);
    }

    /** A contraparte para as subscriptions. */
    public static <T> Multi<T> translating(Multi<T> multi) {
        return multi.onFailure().transform(GraphQlErrors::translate);
    }

    /**
     * A classificação em si.
     *
     * @return a exceção que o SmallRye deve serializar — sempre uma {@link ApplicationGraphQlException},
     *         exceto quando nada casa e o erro genuinamente é interno
     */
    public static Throwable translate(Throwable thrown) {
        // Uma violação de restrição do banco é decisão de domínio, não falha de infraestrutura:
        // traduzida primeiro, ela entra na mesma classificação que uma checagem na aplicação produziria.
        // Vem antes do laço porque a violação chega embrulhada no commit do Axon, longe de onde nasceu.
        Throwable classified = DataIntegrityTranslator.translate(thrown)
                .map(Throwable.class::cast)
                .orElse(thrown);

        for (Throwable t = classified; t != null; t = t.getCause()) {
            if (t instanceof ApplicationGraphQlException || t instanceof GraphQLException) {
                // já classificada — inclusive os cursores inválidos, que o codec recusa na borda
                return t;
            }
            if (t instanceof ConstraintViolationException violations) {
                return new BadRequestException(describe(violations));
            }
            if (t instanceof InvalidPostException || t instanceof PostAlreadyExistsException
                    || t instanceof InvalidTagException || t instanceof TagAlreadyExistsException
                    || t instanceof InvalidUserException
                    || t instanceof AccountAlreadyLinkedException || t instanceof EmailAlreadyInUseException
                    // as duas guardas do mixin SoftDeletable: apagar o apagado, restaurar o vivo
                    || t instanceof AlreadyDeletedException || t instanceof NotDeletedException) {
                return new BadRequestException(t.getMessage());
            }
            // token ausente, expirado ou de outro emissor: tudo "identifique-se"
            if (t instanceof io.quarkus.security.UnauthorizedException
                    || t instanceof io.quarkus.security.AuthenticationFailedException) {
                return new UnauthorizedException("credenciais inválidas ou ausentes");
            }
            // autenticado, mas sem permissão. A mensagem não diz o que faltou, só que faltou
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

    /**
     * Junta as violações numa mensagem estável: ordenadas por caminho, para a mesma requisição inválida
     * produzir sempre o mesmo texto (o {@code Set} do Bean Validation não tem ordem definida).
     */
    private static String describe(ConstraintViolationException ex) {
        return ex.getConstraintViolations().stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .map(ConstraintViolation::getMessage)
                .distinct()
                .collect(Collectors.joining("; "));
    }
}
