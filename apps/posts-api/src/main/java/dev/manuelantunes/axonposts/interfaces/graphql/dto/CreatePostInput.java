package dev.manuelantunes.axonposts.interfaces.graphql.dto;

import org.eclipse.microprofile.graphql.Input;
import org.eclipse.microprofile.graphql.NonNull;

import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Input("CreatePostInput")
public record CreatePostInput(

        @NonNull
        @NotBlank(message = "title must not be blank")
        @Size(max = PostTitle.MAX_LENGTH, message = "title exceeds {max} characters")
        String title,

        @NonNull
        @NotBlank(message = "content must not be blank")
        String content
) {
}
