package dev.manuelantunes.axonposts.application.post.view;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.application.tag.view.TagView;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper
public interface PostViewMapper {
    @Mapping(target = "id", expression = "java(post.id().value())")
    @Mapping(target = "title", expression = "java(post.title().value())")
    @Mapping(target = "content", expression = "java(post.content().value())")
    @Mapping(target = "authorId", expression = "java(post.author().id().value())")
    @Mapping(target = "createdAt", expression = "java(post.createdAt())")
    @Mapping(target = "updatedAt", expression = "java(post.updatedAt())")
    @Mapping(target = "version", expression = "java((int) post.version().value())")
    PostView toView(Post post);

    @Mapping(target = "id", expression = "java(tag.id().value())")
    @Mapping(target = "name", expression = "java(tag.name().value())")
    TagView toTagView(Tag tag);

    List<TagView> toTagViews(List<Tag> tags);
}
