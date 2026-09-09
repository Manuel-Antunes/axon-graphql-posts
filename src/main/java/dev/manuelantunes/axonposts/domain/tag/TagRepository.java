package dev.manuelantunes.axonposts.domain.tag;

import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;

import java.util.Optional;

/**
 * Porta do repositório de Tags. Fica no domínio, como manda o DDD: a interface é vocabulário do domínio,
 * a implementação (Spring Data JPA sobre SQLite) é detalhe de {@code infrastructure}.
 */
public interface TagRepository {

    void save(Tag tag);

    /**
     * A Tag pelo id. Existe para o {@code AssignTagToPostCommand}: agora que o Post referencia a
     * entidade {@code Tag}, assinalar uma tag inexistente violaria a foreign key de {@code post_tags} —
     * carregar antes transforma isso numa recusa de domínio.
     */
    Optional<Tag> findById(TagId tagId);

    /** Usada pelo handler que garante a tag padrão: existe alguma tag com este nome? */
    Optional<Tag> findByName(TagName name);

    /** Existe <b>alguma</b> tag definida no banco? */
    boolean isEmpty();
}
