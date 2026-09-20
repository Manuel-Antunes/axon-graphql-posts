package dev.manuelantunes.axonposts.interfaces.graphql.error;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

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

    private static Exception translated(Exception thrown) {
        Throwable classified = GraphQlErrors.translate(thrown);
        return classified instanceof Exception translated ? translated : thrown;
    }
}
