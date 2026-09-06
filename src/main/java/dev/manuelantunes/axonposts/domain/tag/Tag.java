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
 * Agregado independente, com o seu próprio stream ({@code tagId=<id>}). Um Post guarda apenas uma cópia
 * do id e do nome ({@code TagRef}); nenhuma associação JPA liga os dois, para que a fronteira de
 * consistência de cada um continue sendo só o seu próprio stream.
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
}
