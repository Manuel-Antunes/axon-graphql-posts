package dev.manuelantunes.axonposts.interfaces.graphql.dto;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Input;
import org.eclipse.microprofile.graphql.NonNull;

import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Input("UpdatePostInput")
public record UpdatePostInput(

        @Id
        @NonNull
        @NotBlank(message = "id must not be blank")
        String id,

        @Pattern(regexp = ".*\\S.*", message = "title, when provided, must not be blank")
        @Size(max = PostTitle.MAX_LENGTH, message = "title exceeds {max} characters")
        String title,

        @Pattern(regexp = "(?s).*\\S.*", message = "content, when provided, must not be blank")
        String content
) {
}
