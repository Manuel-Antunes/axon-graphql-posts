package dev.manuelantunes.axonposts.domain.tag.exception;

import dev.manuelantunes.axonposts.domain.tag.vo.TagId;

public class TagAlreadyExistsException extends RuntimeException {
    public TagAlreadyExistsException(TagId tagId) {
        super("Tag já existe: " + tagId);
    }

    public TagAlreadyExistsException() {
        super("já existe uma tag com este nome");
    }
}
