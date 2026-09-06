package dev.manuelantunes.axonposts.mapper;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.vo.TagRef;
import dev.manuelantunes.axonposts.dto.controller.PostView;
import dev.manuelantunes.axonposts.dto.controller.TagView;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Domínio → saída do GraphQL: a entidade {@link Post} vira o DTO {@link PostView}, com os value objects
 * achatados em primitivos.
 * <p>
 * O {@link PostView} não tem tags: elas são um campo resolvido à parte, por DataLoader. O que este mapper
 * empresta para lá é o {@link #toTagViews(List)} — a conversão {@code TagRef} → {@link TagView}, que o
 * MapStruct gera sozinho por serem dois records.
 *
 * <h2>Por que aqui as origens são {@code expression}</h2>
 * O MapStruct descobre propriedades por acessor JavaBean ({@code getTitle()}) ou por componente de
 * {@code record}. O {@link Post} não é nem um nem outro — é uma entidade de domínio com acessores
 * {@code title()} devolvendo value objects — então o mapeamento automático não enxerga campo nenhum.
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

    /** {@code TagRef} e {@code TagView} são records; só o nome do id difere. */
    @Mapping(target = "id", source = "tagId")
    TagView toTagView(TagRef tag);

    List<TagView> toTagViews(List<TagRef> tags);
}
