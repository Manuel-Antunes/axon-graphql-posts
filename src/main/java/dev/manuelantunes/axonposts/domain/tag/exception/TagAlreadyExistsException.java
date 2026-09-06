package dev.manuelantunes.axonposts.domain.tag.exception;

import dev.manuelantunes.axonposts.domain.tag.vo.TagId;

/** Tentativa de criar uma Tag com um id que já tem eventos no stream. */
public class TagAlreadyExistsException extends RuntimeException {

    public TagAlreadyExistsException(TagId tagId) {
        super("Tag já existe: " + tagId);
    }
}
