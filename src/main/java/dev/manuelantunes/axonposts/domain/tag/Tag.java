package dev.manuelantunes.axonposts.domain.tag;

import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;
import dev.manuelantunes.axonposts.domain.tag.event.TagCreatedEvent;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;

import java.time.Instant;
import java.util.Objects;

/**
 * A Tag: como o {@code Post}, uma classe só — entidade de domínio, entidade event-sourced e mapeamento
 * JPA, com os value objects como {@code @Embedded}.
 * <p>
 * Agregado independente, com o seu próprio stream ({@code tagId=<id>}). O {@code Post} referencia a Tag
 * pela própria entidade ({@code @ManyToMany Set<Tag>} sobre {@code post_tags}), mas <b>só de leitura</b>:
 * nenhum cascade sai do Post, e é sempre o Post quem dispara o evento do vínculo. A Tag não tem evento
 * nenhum sobre posts.
 * <p>
 * Hoje uma Tag só nasce — não há evento que a renomeie ou apague, então não existe
 * {@code @EventSourcingHandler}: todo o estado vem do {@link TagCreatedEvent}.
 */
@Entity
@Table(name = "tags")
@EventSourced(tagKey = Tag.TAG_KEY, idType = TagId.class)
public class Tag {

    /** Chave da tag no event store; tem de bater com o {@code @EventTag} do {@link TagCreatedEvent}. */
    public static final String TAG_KEY = "tagId";

    /** Nome da tag atribuída a um post que não tem nenhuma outra. */
    public static final String DEFAULT_NAME = "Untagged";

    @EmbeddedId
    @AttributeOverride(name = "value", column = @Column(name = "id", length = 36, nullable = false))
    private TagId id;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "name", length = 50, nullable = false, unique = true))
    private TagName name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Exigido pelo JPA. */
    protected Tag() {
    }

    // ---- decidir --------------------------------------------------------------------------------

    /**
     * Construtor nomeado da Tag: valida o nome, dispara {@link TagCreatedEvent} e devolve a Tag pronta
     * para ser salva por quem chamou.
     */
    public static Tag create(TagId id, String name, Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        TagCreatedEvent event = new TagCreatedEvent(id, TagName.of(name).value(), now);

        events.raise(event);
        return new Tag(event);
    }

    // ---- referência -----------------------------------------------------------------------------

    /**
     * Uma Tag <b>não carregada</b>: identidade e nome, sem {@code createdAt} — o equivalente ao
     * {@code Ref<Tag>} de um ORM com identity map, ou ao {@code getReference} do Hibernate.
     * <p>
     * <b>Para que existe:</b> quando o Axon reconstitui um {@link dev.manuelantunes.axonposts.domain.post.Post}
     * do stream dele, o {@code PostUpdatedEvent} traz o id e o nome de cada tag, e não há sessão JPA para
     * resolver a entidade de verdade. Buscar no banco ali dentro tornaria o replay dependente do estado
     * atual da tabela — o mesmo stream reconstituído amanhã daria outro Post. Então o replay produz
     * referências, e só elas.
     * <p>
     * <b>O que não fazer com o resultado:</b> nunca salvar. Uma referência tem {@code createdAt} nulo e
     * sobrescreveria a linha real da tag. Ela existe para virar linha em {@code post_tags} — o que o
     * {@code merge} faz pelo id, sem tocar em {@code tags} — e para responder {@link #id()} e
     * {@link #name()} a quem já tem o dado em mãos.
     */
    public static Tag reference(TagId id, TagName name) {
        Tag tag = new Tag();
        tag.id = Objects.requireNonNull(id, "id");
        tag.name = Objects.requireNonNull(name, "name");
        return tag;
    }

    /** {@code true} se esta instância é uma referência do replay, e não uma Tag carregada. */
    public boolean isReference() {
        return createdAt == null;
    }

    // ---- evoluir --------------------------------------------------------------------------------

    /** O Axon chama com o primeiro (e único) evento do stream da Tag. */
    @EntityCreator
    public Tag(TagCreatedEvent event) {
        this.id = event.tagId();
        this.name = TagName.of(event.name());
        this.createdAt = event.occurredAt();
    }

    // ---- estado ---------------------------------------------------------------------------------

    public TagId id() {
        return id;
    }

    public TagName name() {
        return name;
    }

    public Instant createdAt() {
        return createdAt;
    }

    // ---- identidade -----------------------------------------------------------------------------

    /**
     * Duas Tags são a mesma se têm o mesmo id — <b>não</b> o mesmo estado. É o que define entidade, e
     * agora é obrigatório: as tags de um Post vivem num {@code Set}, onde uma referência do replay e a
     * entidade carregada do banco têm de colidir para o mesmo elemento.
     * <p>
     * {@code instanceof} em vez de {@code getClass()}: o Hibernate entrega proxies, que são subclasses.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Tag tag && id != null && id.equals(tag.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return name == null ? String.valueOf(id) : name.value();
    }
}
