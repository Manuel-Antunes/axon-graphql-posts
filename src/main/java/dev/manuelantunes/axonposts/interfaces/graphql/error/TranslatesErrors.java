package dev.manuelantunes.axonposts.interfaces.graphql.error;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.interceptor.InterceptorBinding;

/**
 * Marca uma classe (ou um método) cujo erro deve sair traduzido para o protocolo GraphQL.
 * <p>
 * É o substituto do {@code @GraphQlExceptionHandler} do Spring nesta conversão: uma anotação por
 * {@code @GraphQLApi}, e todo resolver da classe passa por {@link ErrorTranslationInterceptor} — inclusive
 * os que ainda nem foram escritos. Antes era uma chamada a {@code GraphQlErrors.translating(...)} por
 * operação, que um resolver novo podia esquecer.
 *
 * <h2>Por que a anotação é da classe e não do pacote</h2>
 * Porque CDI só conhece binding em tipo e em método. Um <i>stereotype</i> que juntasse
 * {@code @GraphQLApi + @ApplicationScoped + @TranslatesErrors} numa anotação só seria mais curto, mas
 * quebraria o schema: o construtor do SmallRye varre o índice Jandex por {@code @GraphQLApi}
 * <b>direta</b>, e a classe sumiria do schema em silêncio.
 */
@InterceptorBinding
@Inherited
@Retention(RUNTIME)
@Target({ TYPE, METHOD })
public @interface TranslatesErrors {
}
