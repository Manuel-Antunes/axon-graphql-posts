package dev.manuelantunes.axonposts.domain.tag.exception;

import dev.manuelantunes.axonposts.domain.tag.vo.TagId;

public class TagNotFoundException extends RuntimeException {
    public TagNotFoundException(TagId tagId) {
        super("Tag não encontrada: " + tagId);
    }
}
