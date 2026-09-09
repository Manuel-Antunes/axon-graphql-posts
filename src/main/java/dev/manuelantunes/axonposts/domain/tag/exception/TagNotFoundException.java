package dev.manuelantunes.axonposts.domain.tag.exception;

import dev.manuelantunes.axonposts.domain.tag.vo.TagId;

/**
 * Tentativa de assinalar a um post uma Tag que não existe.
 * <p>
 * Passou a ser necessária quando o Post deixou de guardar uma cópia da tag e passou a referenciar a
 * entidade {@code Tag}: sem a checagem, o id inexistente só apareceria como violação de foreign key na
 * hora do INSERT em {@code post_tags}.
 */
public class TagNotFoundException extends RuntimeException {

    public TagNotFoundException(TagId tagId) {
        super("Tag não encontrada: " + tagId);
    }
}
