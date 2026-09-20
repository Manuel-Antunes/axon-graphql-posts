package dev.manuelantunes.axonposts.interfaces.graphql.mapper;

import dev.manuelantunes.axonposts.application.post.command.CreatePostCommand.CreatePost;
import dev.manuelantunes.axonposts.application.post.command.UpdatePostCommand.UpdatePost;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.interfaces.graphql.dto.CreatePostInput;
import dev.manuelantunes.axonposts.interfaces.graphql.dto.UpdatePostInput;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface PostInputMapper {
    @Mapping(target = "postId", source = "postId")
    @Mapping(target = "title", source = "input.title")
    @Mapping(target = "content", source = "input.content")
    @Mapping(target = "authorId", source = "authorId")
    @Mapping(target = "tagIds", expression = "java(java.util.List.of())")
    CreatePost toCommand(PostId postId, CreatePostInput input, UserId authorId);

    @Mapping(target = "postId", source = "input.id")
    @Mapping(target = "title", source = "input.title")
    @Mapping(target = "content", source = "input.content")
    @Mapping(target = "actingAuthor", source = "actingAuthor")
    UpdatePost toCommand(UpdatePostInput input, UserId actingAuthor);

    default PostId toPostId(String value) {
        return PostId.of(value);
    }
}
