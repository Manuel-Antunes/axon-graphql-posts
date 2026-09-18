package dev.manuelantunes.axonposts.interfaces.graphql.dto;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Input;
import org.eclipse.microprofile.graphql.NonNull;

import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Input GraphQL de {@code updatePost}; {@code title}/{@code content} nulos significam "manter".
 * <p>
 * O "nulo pode, vazio não" é expresso com {@code @Pattern}: constraints de Bean Validation são
 * <b>ignoradas quando o valor é {@code null}</b>, então o padrão "pelo menos um caractere não-branco"
 * só é cobrado de quem realmente mandou o campo. {@code @NotBlank} aqui deixaria de aceitar a omissão,
 * que é justamente a semântica de update parcial.
 */
@Input("UpdatePostInput")
public record UpdatePostInput(

        @Id
        @NonNull
        @NotBlank(message = "id não pode ser vazio")
        String id,

        @Pattern(regexp = ".*\\S.*", message = "title, quando informado, não pode ser vazio")
        @Size(max = PostTitle.MAX_LENGTH, message = "title excede {max} caracteres")
        String title,

        @Pattern(regexp = "(?s).*\\S.*", message = "content, quando informado, não pode ser vazio")
        String content
) {
}
