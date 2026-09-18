package dev.manuelantunes.axonposts.domain.tag.exception;

import dev.manuelantunes.axonposts.domain.tag.vo.TagId;

/** Tentativa de criar uma Tag com um id que já tem eventos no stream. */
public class TagAlreadyExistsException extends RuntimeException {

    public TagAlreadyExistsException(TagId tagId) {
        super("Tag já existe: " + tagId);
    }

    /** Sem id: levantada pelo {@code DataIntegrityTranslator} a partir de {@code uk_tags_name}. */
    public TagAlreadyExistsException() {
        super("já existe uma tag com este nome");
    }
}
