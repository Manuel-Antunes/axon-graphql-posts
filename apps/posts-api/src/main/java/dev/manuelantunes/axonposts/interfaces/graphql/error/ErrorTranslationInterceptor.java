package dev.manuelantunes.axonposts.interfaces.graphql.error;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * O "exception handler" que o SmallRye não tem: um interceptador CDI em volta de cada resolver.
 *
 * <h2>Por que isto funciona aqui</h2>
 * O SmallRye não instancia o {@code @GraphQLApi} — ele o pede ao {@code LookupService}, que no Quarkus é
 * o CDI ({@code Instance.select(...).get()}). O que volta é o <i>client proxy</i> do ArC, então a chamada
 * do {@code ReflectionInvoker} entra pela cadeia de interceptadores como qualquer outra. É a mesma razão
 * pela qual {@code @RolesAllowed} e {@code @Valid} já funcionavam nos resolvers — só faltava usar o
 * mecanismo também para o erro.
 *
 * <h2>Os dois caminhos de falha, e por que os dois importam</h2>
 * <ul>
 *   <li><b>assíncrono</b>: o resolver devolve um {@link Uni}/{@link Multi} que falha depois. É o caminho
 *       que {@code GraphQlErrors.translating(...)} cobria;</li>
 *   <li><b>síncrono</b>: o resolver lança antes de montar o {@code Uni} — um cursor inválido, um
 *       {@code @Valid} que estourou. Esse caminho <b>não passava</b> pela tradução, porque a chamada
 *       estava dentro do corpo do método.</li>
 * </ul>
 * Aqui os dois convergem no mesmo {@link GraphQlErrors}, que continua sendo a única classificação.
 *
 * <h2>Prioridade 100: por fora de tudo, inclusive da segurança</h2>
 * Os interceptadores de segurança do Quarkus são {@code @Priority(150)}, e prioridade menor roda por
 * <b>fora</b>. Ficar em {@code APPLICATION} (2000) deixava as recusas de segurança escaparem sem
 * tradução, e o resultado era feio de três jeitos ao mesmo tempo:
 * <ul>
 *   <li>{@code "message": null} — {@code io.quarkus.security.UnauthorizedException} não tem mensagem, e
 *       listá-la em {@code show-runtime-exception-message} só fazia o SmallRye publicar o {@code null};</li>
 *   <li>{@code "code": "unauthorized"} em minúsculas, derivado do nome da classe, ao lado dos
 *       {@code BAD_REQUEST}/{@code FORBIDDEN}/{@code NOT_FOUND} do projeto;</li>
 *   <li>token malformado nem virava erro de GraphQL: a exceção subia até o transporte e o cliente
 *       recebia <b>HTTP 401 com corpo vazio</b>, sem {@code errors}.</li>
 * </ul>
 * Por fora, os três somem: a recusa vira uma {@link ApplicationGraphQlException} com mensagem e código,
 * como qualquer outra. A distinção que o Quarkus dá de graça — sem token é uma coisa, com token e sem a
 * role é outra — <b>continua</b>, agora como {@code UNAUTHORIZED} e {@code FORBIDDEN}.
 * <p>
 * O log melhora junto, e pelo mesmo motivo: o que chega ao {@code SRGQL012000} deixa de ser a
 * {@code AuthenticationFailedException} crua, com a pilha inteira do Mutiny, uma
 * {@code CompositeException} e um {@code CIRCULAR REFERENCE} — e passa a ser uma exceção rasa, sem causa,
 * com a mensagem que o cliente também recebeu. O que <b>não</b> é esperado continua subindo inteiro, com
 * causa e pilha: {@link GraphQlErrors#translate} devolve o original quando nada casa.
 */
@Interceptor
@TranslatesErrors
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 100)
public class ErrorTranslationInterceptor {

    @AroundInvoke
    Object translating(InvocationContext context) throws Exception {
        Object result;
        try {
            result = context.proceed();
        } catch (Exception thrown) {
            throw translated(thrown);
        }

        if (result instanceof Uni<?> uni) {
            return GraphQlErrors.translating(uni);
        }
        if (result instanceof Multi<?> multi) {
            return GraphQlErrors.translating(multi);
        }
        return result;
    }

    /**
     * {@code GraphQlErrors} classifica {@link Throwable} porque é o que chega de um {@code CompletableFuture};
     * um interceptador só pode relançar {@link Exception}. Quando a classificação devolve um {@code Error}
     * (nunca acontece hoje), o original é relançado sem tradução — perder o erro seria pior.
     */
    private static Exception translated(Exception thrown) {
        Throwable classified = GraphQlErrors.translate(thrown);
        return classified instanceof Exception translated ? translated : thrown;
    }
}
