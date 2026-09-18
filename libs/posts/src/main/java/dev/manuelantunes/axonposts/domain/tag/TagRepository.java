package dev.manuelantunes.axonposts.domain.tag;

import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Porta do repositório de Tags. Fica no domínio, como manda o DDD: a interface é vocabulário do domínio,
 * a implementação (Spring Data JPA sobre PostgreSQL) é detalhe de {@code infrastructure}.
 */
public interface TagRepository {

    void save(Tag tag);

    /**
     * Grava a tag <b>só se ainda não houver</b> uma com este id, e nunca falha se houver.
     *
     * <h3>Por que existe, além de {@link #save(Tag)}</h3>
     * Porque há um caso em que a tag não é uma decisão deste serviço: ela chega dentro de um evento
     * vindo de outro, e o que se faz aqui é <b>projetar um fato</b>. Nessa situação "já existe" não é
     * conflito, é o estado desejado — e o {@code merge} do JPA não sabe disso: ele decide entre insert e
     * update pelo que a sessão conhece, e duas threads decidindo ao mesmo tempo produzem
     * {@code duplicate key value violates unique constraint "tags_pkey"}, que aborta a transação
     * inteira.
     * <p>
     * A implementação resolve no banco, que é o único lugar onde a decisão é serializável.
     */
    void saveIfAbsent(Tag tag);

    /**
     * A Tag pelo id. Existe para o {@code AssignTagToPostCommand}: agora que o Post referencia a
     * entidade {@code Tag}, assinalar uma tag inexistente violaria a foreign key de {@code post_tags} —
     * carregar antes transforma isso numa recusa de domínio.
     */
    Optional<Tag> findById(TagId tagId);

    /** Usada pelo handler que garante a tag padrão: existe alguma tag com este nome? */
    Optional<Tag> findByName(TagName name);

    List<Tag> findAllById(Collection<TagId> tagIds);

    /** Existe <b>alguma</b> tag definida no banco? */
    boolean isEmpty();
}
