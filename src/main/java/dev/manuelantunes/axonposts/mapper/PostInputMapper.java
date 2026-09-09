package dev.manuelantunes.axonposts.mapper;

import dev.manuelantunes.axonposts.application.post.command.CreatePostCommand.CreatePost;
import dev.manuelantunes.axonposts.application.post.command.UpdatePostCommand.UpdatePost;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.dto.controller.CreatePostInput;
import dev.manuelantunes.axonposts.dto.controller.UpdatePostInput;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Protocolo → aplicação: input do GraphQL vira command.
 * <p>
 * Input e command são records de forma parecida, que é exatamente onde o MapStruct rende mais: ele lê os
 * componentes do record de origem e chama o construtor canônico do de destino, sem uma linha escrita à
 * mão.
 */
@Mapper(componentModel = "spring")
public interface PostInputMapper {

    /**
     * O {@link PostId} entra como parâmetro em vez de ser gerado aqui: mapper é tradução, não fábrica de
     * identidade. Quem decide o id é o controller, e é por isso que ele consegue devolver o Post criado
     * na mesma resposta.
     * <p>
     * O {@code authorId} também é parâmetro, e por um motivo mais forte: ele não <b>existe</b> no input.
     * Vem do token, e o controller o busca antes de chamar aqui.
     */
    @Mapping(target = "postId", source = "postId")
    @Mapping(target = "title", source = "input.title")
    @Mapping(target = "content", source = "input.content")
    @Mapping(target = "authorId", source = "authorId")
    CreatePost toCommand(PostId postId, CreatePostInput input, UserId authorId);

    @Mapping(target = "postId", source = "id")
    UpdatePost toCommand(UpdatePostInput input);

    /** Conversão usada pelo MapStruct para o {@code id} do update: {@code String} → {@link PostId}. */
    default PostId toPostId(String value) {
        return PostId.of(value);
    }
}
