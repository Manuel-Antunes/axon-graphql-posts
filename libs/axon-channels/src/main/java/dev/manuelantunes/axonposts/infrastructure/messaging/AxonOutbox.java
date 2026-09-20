package dev.manuelantunes.axonposts.infrastructure.messaging;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;

@Qualifier
@Retention(RUNTIME)
@Target({METHOD, FIELD, PARAMETER, TYPE})
public @interface AxonOutbox {
    @Nonbinding
    String channel() default "";

    @Nonbinding
    String[] namespaces() default {};

    final class Literal extends AnnotationLiteral<AxonOutbox> implements AxonOutbox {
        public static final Literal ANY = new Literal();

        private static final long serialVersionUID = 1L;

        @Override
        public String channel() {
            return "";
        }

        @Override
        public String[] namespaces() {
            return new String[0];
        }
    }
}
