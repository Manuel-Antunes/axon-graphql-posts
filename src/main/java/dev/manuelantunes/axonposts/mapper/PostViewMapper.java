package dev.manuelantunes.axonposts.mapper;

import dev.manuelantunes.axonposts.application.post.PostView;
import dev.manuelantunes.axonposts.domain.post.Post;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Domínio → aplicação: a entidade {@link Post} vira o read model {@link PostView}. É o que o command
 * handler usa para salvar o Post que o domínio acabou de devolver.
 *
 * <h2>Por que aqui as origens são {@code expression}</h2>
 * O MapStruct descobre propriedades por acessor JavaBean ({@code getTitle()}) ou por componente de
 * {@code record}. O {@link Post} não é nem um nem outro — é uma classe de domínio com acessores
 * {@code title()} devolvendo value objects — então o mapeamento automático não enxerga campo nenhum
 * (o build falha com "Unmapped target properties" se as {@code expression} saírem).
 * <p>
 * O que <b>não</b> se perde ao escrever a origem à mão: o destino continua conferido pelo compilador.
 * Com {@code -Amapstruct.unmappedTargetPolicy=ERROR}, um campo novo em {@link PostView} sem
 * {@code @Mapping} quebra o build — que é a razão de o mapeamento estar no MapStruct e não num método
 * solto, onde o campo novo simplesmente ficaria {@code null}.
 */
@Mapper(componentModel = "spring")
public interface PostViewMapper {

    @Mapping(target = "id", expression = "java(post.id().value())")
    @Mapping(target = "title", expression = "java(post.title().value())")
    @Mapping(target = "content", expression = "java(post.content().value())")
    @Mapping(target = "author", expression = "java(post.author().value())")
    @Mapping(target = "createdAt", expression = "java(post.createdAt())")
    @Mapping(target = "updatedAt", expression = "java(post.updatedAt())")
    @Mapping(target = "version", expression = "java(post.version().value())")
    PostView toView(Post post);
}
