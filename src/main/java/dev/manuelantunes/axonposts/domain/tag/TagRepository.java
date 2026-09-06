package dev.manuelantunes.axonposts.domain.tag;

import dev.manuelantunes.axonposts.domain.tag.vo.TagName;

import java.util.Optional;

/**
 * Porta do repositório de Tags. Fica no domínio, como manda o DDD: a interface é vocabulário do domínio,
 * a implementação (Spring Data JPA sobre SQLite) é detalhe de {@code infrastructure}.
 */
public interface TagRepository {

    void save(Tag tag);

    /** Usada pelo handler que garante a tag padrão: existe alguma tag com este nome? */
    Optional<Tag> findByName(TagName name);

    /** Existe <b>alguma</b> tag definida no banco? */
    boolean isEmpty();
}
