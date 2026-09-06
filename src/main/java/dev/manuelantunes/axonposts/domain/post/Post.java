package dev.manuelantunes.axonposts.domain.post;

import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.vo.Author;
import dev.manuelantunes.axonposts.domain.post.vo.PostContent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import dev.manuelantunes.axonposts.domain.post.vo.PostVersion;
import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;

import java.time.Instant;
import java.util.Objects;

/**
 * Entidade de domínio do Post, no modelo de <b>entidades anotadas</b> do Axon Framework 5.
 * <p>
 * Não existe mais "aggregate root" com {@code @AggregateIdentifier} e {@code AggregateLifecycle.apply}:
 * <ul>
 *   <li>{@code @EventSourced(tagKey = "postId", idType = PostId.class)} registra a entidade no Spring Boot
 *       (estereótipo que embrulha {@code @EventSourcedEntity}). O stream dela são os eventos com a tag
 *       {@code postId=<id>} — o dynamic consistency boundary do Axon 5;</li>
 *   <li>a entidade é <b>imutável</b>: todos os campos {@code final}, e evoluir devolve outra instância;</li>
 *   <li>o estado é feito de value objects ({@code domain.post.vo}), donos das próprias invariantes.</li>
 * </ul>
 *
 * <h2>Decidir devolve o Post pronto para salvar</h2>
 * {@link #create} e {@link #update} fazem duas coisas: <b>disparam</b> o evento pela porta
 * {@link DomainEventPublisher} e <b>devolvem o estado resultante</b>. Quem chama recebe o Post já
 * construído e o grava no read model — não precisa esperar o evento voltar para saber como o Post ficou.
 * <p>
 * O truque que mantém as duas metades coerentes: o estado devolvido é produzido pelos <i>mesmos</i>
 * métodos que o Axon usa para reconstituir a entidade do stream ({@link #createdFrom} e {@link #on}).
 * Existe um único lugar que define como um evento vira estado, então "o que eu acabei de salvar" e "o
 * que sai de um replay" não podem divergir.
 *
 * <h2>Command handlers ficam fora</h2>
 * A entidade não tem {@code @CommandHandler}. Cada command tem a sua classe em
 * {@code application.post.command} — é lá que se decide <i>quando</i> chamar {@link #create} ou
 * {@link #update} e o que fazer com o Post devolvido; aqui só mora o <i>o quê</i> e o <i>se pode</i>.
 * E <i>ouvir</i> o evento disparado é da aplicação também ({@code application.post.event}).
 */
@EventSourced(tagKey = Post.TAG_KEY, idType = PostId.class)
public final class Post {

    /** Chave da tag no event store; tem de bater com o nome do campo {@code @EventTag} dos eventos. */
    public static final String TAG_KEY = "postId";

    private final PostId id;
    private final PostTitle title;
    private final PostContent content;
    private final Author author;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final PostVersion version;

    private Post(PostId id,
                 PostTitle title,
                 PostContent content,
                 Author author,
                 Instant createdAt,
                 Instant updatedAt,
                 PostVersion version) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.author = author;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    // ---- decidir: dispara o evento e devolve o estado resultante --------------------------------

    /**
     * Construtor nomeado do Post: valida os dados, <b>dispara</b> {@link PostCreatedEvent} e devolve o
     * Post já criado, pronto para ser salvo por quem chamou.
     * <p>
     * Os textos entram crus e viram value objects na hora — se algum for inválido, nada é disparado e
     * nada é devolvido.
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
        return createdFrom(event);
    }

    /**
     * Decide uma atualização parcial, <b>dispara</b> {@link PostUpdatedEvent} com o estado resultante e
     * devolve o Post atualizado. Campos {@code null} significam "não mexer".
     * <p>
     * Um update que não muda nada é rejeitado: evento inútil no stream é ruído que as subscriptions
     * propagariam adiante.
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

        PostUpdatedEvent event = new PostUpdatedEvent(
                id, resultingTitle.value(), resultingContent.value(), now
        );

        events.raise(event);
        return on(event);
    }

    // ---- evoluir: reconstituição a partir do stream ---------------------------------------------

    /**
     * Construtor nomeado do event sourcing: o Axon chama com o <b>primeiro</b> evento do stream, e
     * {@link #create} chama com o evento que acabou de disparar.
     * <p>
     * Sem evento nenhum, o Axon não constrói nada — {@code @InjectEntity Post} lança
     * {@code EntityNotFoundException} e {@code @InjectEntity Optional<Post>} vem vazio. É exatamente
     * essa diferença que os dois command handlers usam.
     */
    @EntityCreator
    public static Post createdFrom(PostCreatedEvent event) {
        return new Post(
                event.postId(),
                PostTitle.of(event.title()),
                PostContent.of(event.content()),
                Author.of(event.author()),
                event.occurredAt(),
                event.occurredAt(),
                PostVersion.initial()
        );
    }

    /**
     * Evolução imutável: devolve o próximo estado em vez de mutar este. O Axon 5 aceita o retorno do
     * {@code @EventSourcingHandler} como o estado evoluído; {@link #update} usa o mesmo método para
     * devolver o Post atualizado a quem despachou o command.
     */
    @EventSourcingHandler
    public Post on(PostUpdatedEvent event) {
        return new Post(
                id,
                PostTitle.of(event.title()),
                PostContent.of(event.content()),
                author,
                createdAt,
                event.occurredAt(),
                version.next()
        );
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
}
