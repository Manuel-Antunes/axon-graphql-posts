package dev.manuelantunes.axonposts.dto.controller;

import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Input GraphQL de {@code createPost}.
 * <p>
 * O schema já garante que os três campos vêm não-nulos ({@code String!}); as constraints abaixo cobrem
 * o que o GraphQL não sabe expressar — texto só de espaços e título longo demais.
 *
 * <h2>Isto não substitui a validação do domínio</h2>
 * {@code PostTitle}, {@code PostContent} e {@code Author} continuam validando por conta própria, e é
 * essa a validação que vale: ela protege a invariante venha o command de onde vier. As constraints aqui
 * são <b>fail-fast de borda</b> — rejeitam a requisição malformada antes de gastar um command, e
 * devolvem ao cliente um erro de campo em vez de uma exceção de domínio traduzida. O limite duplicado é
 * amarrado à fonte: {@link PostTitle#MAX_LENGTH}.
 */
public record CreatePostInput(

        @NotBlank(message = "title não pode ser vazio")
        @Size(max = PostTitle.MAX_LENGTH, message = "title excede {max} caracteres")
        String title,

        @NotBlank(message = "content não pode ser vazio")
        String content,

        @NotBlank(message = "author não pode ser vazio")
        String author
) {
}
