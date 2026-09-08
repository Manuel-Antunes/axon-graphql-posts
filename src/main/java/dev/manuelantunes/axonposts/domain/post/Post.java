package dev.manuelantunes.axonposts.domain.post;

import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.vo.Author;
import dev.manuelantunes.axonposts.domain.post.vo.PostContent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import dev.manuelantunes.axonposts.domain.post.vo.PostVersion;
import dev.manuelantunes.axonposts.domain.post.vo.TagRef;
import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * O Post: <b>uma</b> classe que é ao mesmo tempo a entidade de domínio, a entidade event-sourced do Axon
 * e o mapeamento JPA. Não existe um "PostEntity" espelho para manter em sincronia.
 *
 * <h2>As três anotações e por que elas convivem</h2>
 * <ul>
 *   <li>{@code @Entity} + {@code @Table}: o estado atual é gravado direto, com os value objects como
 *       {@code @Embedded} e as tags como {@code @ElementCollection}. JPA é agnóstico de banco, então o
 *       mapeamento vale para SQLite, Postgres ou qualquer outro;</li>
 *   <li>{@code @EventSourced(tagKey = "postId", idType = PostId.class)}: o histórico são os eventos com
 *       a tag {@code postId=<id>}, e é deles que o Axon reidrata a entidade ao tratar um command;</li>
 *   <li>o comportamento: {@link #create}, {@link #update} e {@link #assignTag} validam invariantes e
 *       disparam eventos. Nada aqui é getter/setter puro — não é um registro anêmico com regra
 *       espalhada por serviços.</li>
 * </ul>
 *
 * <h2>Mutável, e por quê</h2>
 * O JPA exige construtor sem argumentos e campos não-finais para poder gerenciar a instância; os
 * {@code @EventSourcingHandler} então <b>mutam</b> o estado em vez de devolver uma cópia (o Axon 5
 * suporta os dois estilos). A entidade continua sem setters públicos: só os eventos mudam o estado, e
 * só as decisões produzem eventos.
 *
 * <h2>Duas metades, o mesmo caminho</h2>
 * <b>Decidir</b> valida, dispara o evento pela porta {@link DomainEventPublisher} e devolve o Post
 * pronto para salvar. <b>Evoluir</b> ({@link #on}) aplica o evento ao estado. Decidir termina chamando
 * evoluir, então "o que o command salvou" e "o que sai de um replay" não podem divergir.
 * <p>
 * Os commands ficam fora, um por arquivo, em {@code application.post.command}. Ouvir os eventos
 * disparados é da aplicação também ({@code application.post.event}).
 */
@Entity
@Table(name = "posts")
@EventSourced(tagKey = Post.TAG_KEY, idType = PostId.class)
public class Post {

    /** Chave da tag no event store; tem de bater com o nome do campo {@code @EventTag} dos eventos. */
    public static final String TAG_KEY = "postId";

    @EmbeddedId
    @AttributeOverride(name = "value", column = @Column(name = "id", length = 36, nullable = false))
    private PostId id;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "title", length = 200, nullable = false))
    private PostTitle title;

    // TEXT em vez de @Lob: o driver sqlite-jdbc não implementa a API de CLOB do JDBC
    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "content", columnDefinition = "TEXT", nullable = false))
    private PostContent content;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "author", nullable = false))
    private Author author;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "version", nullable = false))
    private PostVersion version;

    /**
     * As tags do post, gravadas direto em {@code post_tags} como coleção de embeddables — o estado do
     * relacionamento mora no próprio agregado, sem entidade de ligação nem associação a {@code Tag}.
     * <p>
     * {@code LAZY}: no caminho de leitura ninguém navega por aqui. Quem serve as tags ao GraphQL é um
     * DataLoader, que as busca em lote para todos os posts da resposta numa consulta só — com
     * {@code EAGER} o Hibernate faria um SELECT por post e o lote não teria o que evitar.
     * <p>
     * No caminho de escrita a coleção sempre está carregada, porque a entidade que o command
     * salva vem reconstituída dos eventos pelo Axon, não do banco.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "post_tags", joinColumns = @JoinColumn(name = "post_id"))
    private Set<TagRef> tags = new LinkedHashSet<>();

    /** Exigido pelo JPA. Nenhum código de aplicação constrói um Post por aqui. */
    protected Post() {
    }

    // ---- decidir: dispara o evento e devolve o estado resultante --------------------------------

    /**
     * Construtor nomeado do Post: valida os dados, <b>dispara</b> {@link PostCreatedEvent} e devolve o
     * Post já criado, pronto para ser salvo por quem chamou. Nasce sem tags.
     *
     * @throws InvalidPostException se title, content ou author violarem suas invariantes
     */
    public static Post create(PostId id,
                              String title,
                              String content,
                              String author,
                              Instant now,
                              DomainEventPublisher events) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        PostCreatedEvent event = new PostCreatedEvent(
                id,
                PostTitle.of(title).value(),
                PostContent.of(content).value(),
                Author.of(author).value(),
                now
        );

        events.raise(event);
        return new Post(event);
    }

    /**
     * Decide uma atualização parcial de título e/ou conteúdo, dispara {@link PostUpdatedEvent} com o
     * estado resultante e devolve o Post atualizado. Campos {@code null} significam "não mexer"; as tags
     * não mudam aqui.
     *
     * @throws InvalidPostException se um valor informado for inválido, ou se nada mudar
     */
    public Post update(String newTitle, String newContent, Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        PostTitle resultingTitle = newTitle == null ? this.title : PostTitle.of(newTitle);
        PostContent resultingContent = newContent == null ? this.content : PostContent.of(newContent);

        if (resultingTitle.equals(this.title) && resultingContent.equals(this.content)) {
            throw new InvalidPostException("update sem mudanças: informe um title e/ou content diferente do atual");
        }

        return raiseUpdate(resultingTitle, resultingContent, this.tags, now, events);
    }

    /**
     * Assinala uma tag ao post e dispara {@link PostUpdatedEvent} com a lista de tags resultante —
     * o mesmo evento de update, porque a tag faz parte do estado do post, não de um ciclo de vida à parte.
     * <p>
     * Assinalar uma tag que o post já tem é rejeitado pelo mesmo motivo que um update sem mudanças: não
     * há fato novo a registrar.
     *
     * @throws InvalidPostException se o post já tiver essa tag
     */
    public Post assignTag(TagRef tag, Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        if (hasTag(tag.tagId())) {
            throw new InvalidPostException("post já tem a tag " + tag.name());
        }

        Set<TagRef> resulting = new LinkedHashSet<>(this.tags);
        resulting.add(tag);
        return raiseUpdate(this.title, this.content, resulting, now, events);
    }

    private Post raiseUpdate(PostTitle resultingTitle,
                             PostContent resultingContent,
                             Set<TagRef> resultingTags,
                             Instant now,
                             DomainEventPublisher events) {
        PostUpdatedEvent event = new PostUpdatedEvent(
                id,
                resultingTitle.value(),
                resultingContent.value(),
                resultingTags.stream().map(t -> new PostUpdatedEvent.Tag(t.tagId(), t.name())).toList(),
                this.version.next().value(),
                now
        );

        events.raise(event);
        on(event);
        return this;
    }

    public boolean hasTag(String tagId) {
        return tags.stream().anyMatch(tag -> tag.tagId().equals(tagId));
    }

    public boolean hasNoTags() {
        return tags.isEmpty();
    }

    // ---- evoluir: reconstituição a partir do stream ---------------------------------------------

    /**
     * Construtor nomeado do event sourcing: o Axon chama com o <b>primeiro</b> evento do stream, e
     * {@link #create} chama com o evento que acabou de disparar.
     * <p>
     * Sem evento nenhum, o Axon não constrói nada — {@code @InjectEntity Post} lança
     * {@code EntityNotFoundException} e {@code @InjectEntity Optional<Post>} vem vazio. É exatamente
     * essa diferença que os commands usam.
     */
    @EntityCreator
    public Post(PostCreatedEvent event) {
        this.id = event.postId();
        this.title = PostTitle.of(event.title());
        this.content = PostContent.of(event.content());
        this.author = Author.of(event.author());
        this.createdAt = event.occurredAt();
        this.updatedAt = event.occurredAt();
        this.version = PostVersion.initial();
        this.tags = new LinkedHashSet<>();
    }

    /**
     * Aplica um update ao estado. Muta a instância porque ela é gerenciada pelo JPA — mas continua sendo
     * o único caminho pelo qual o estado muda, tanto no replay quanto logo depois de uma decisão.
     * <p>
     * <b>Idempotente</b>: cada campo recebe um valor absoluto vindo do evento, versão inclusive. Tem de
     * ser assim porque numa entidade mutável o mesmo evento chega por dois caminhos — o
     * {@code raiseUpdate} aplica ao decidir, e o Axon aplica ao apendar. Um {@code version.next()} aqui
     * contaria duas vezes.
     */
    @EventSourcingHandler
    public void on(PostUpdatedEvent event) {
        this.title = PostTitle.of(event.title());
        this.content = PostContent.of(event.content());
        this.updatedAt = event.occurredAt();
        this.version = new PostVersion(event.version());

        Set<TagRef> next = new LinkedHashSet<>();
        event.tags().forEach(tag -> next.add(TagRef.of(tag.tagId(), tag.name())));
        this.tags.clear();
        this.tags.addAll(next);
    }

    // ---- estado ---------------------------------------------------------------------------------

    public PostId id() {
        return id;
    }

    public PostTitle title() {
        return title;
    }

    public PostContent content() {
        return content;
    }

    public Author author() {
        return author;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public PostVersion version() {
        return version;
    }

    public List<TagRef> tags() {
        return List.copyOf(tags);
    }
}
