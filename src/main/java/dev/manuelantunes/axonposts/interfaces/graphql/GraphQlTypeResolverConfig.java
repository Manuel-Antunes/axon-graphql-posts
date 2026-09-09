package dev.manuelantunes.axonposts.interfaces.graphql;

import dev.manuelantunes.axonposts.dto.controller.AuthorView;
import dev.manuelantunes.axonposts.dto.controller.ReaderView;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.ClassNameTypeResolver;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

/**
 * Diz ao graphql-java qual tipo concreto um {@code User} é, em runtime.
 *
 * <h2>O problema</h2>
 * Uma query {@code me { ... on Author { bio } }} devolve um objeto Java e o graphql-java precisa
 * decidir se ele é um {@code Reader} ou um {@code Author} do schema. O {@link ClassNameTypeResolver}
 * padrão do Spring resolve pelo <b>nome simples da classe</b> — o que daria {@code ReaderView} e
 * {@code AuthorView}, tipos que não existem no schema.
 *
 * <h2>Por que não renomear os records para bater</h2>
 * Porque o nome {@code AuthorView} é útil de dentro: ele diz que aquilo é DTO de saída, não a entidade
 * {@code Author}, e num projeto onde os dois convivem essa distinção evita import errado. Mapear dois
 * pares aqui é mais barato do que perder isso em todo o código.
 * <p>
 * O mapeamento é registrado no tipo {@code User}, a interface — é ela que precisa de resolver, e é ela
 * que o {@code me} devolve.
 */
@Configuration
public class GraphQlTypeResolverConfig {

    @Bean
    RuntimeWiringConfigurer userTypeResolver() {
        ClassNameTypeResolver resolver = new ClassNameTypeResolver();
        resolver.addMapping(ReaderView.class, "Reader");
        resolver.addMapping(AuthorView.class, "Author");

        return wiring -> wiring.type("User", builder -> builder.typeResolver(resolver));
    }
}
