package dev.manuelantunes.axonposts.domain.post;

import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostDeletedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostRestoredEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.exception.NotThePostAuthorException;
import dev.manuelantunes.axonposts.domain.post.vo.PostContent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import dev.manuelantunes.axonposts.domain.post.vo.PostVersion;
import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;
import dev.manuelantunes.axonposts.domain.shared.SoftDeletable;
import dev.manuelantunes.axonposts.domain.shared.SoftDeletion;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * O Post: <b>uma</b> classe que é ao mesmo tempo a entidade de domínio, a entidade event-sourced do Axon
 * e o mapeamento JPA. Não existe um "PostEntity" espelho para manter em sincronia.
 *
 * <h2>As três anotações e por que elas convivem</h2>
 * <ul>
 *   <li>{@code @Entity} + {@code @Table}: o estado atual é gravado direto, com os value objects como
 *       {@code @Embedded} e as tags como {@code @ManyToMany}. JPA é agnóstico de banco, então o
 *       mapeamento vale para Postgres, MySQL ou qualquer outro;</li>
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
/*
 * Exclusão lógica: @SQLRestriction esconde os apagados de toda consulta, @SQLDelete troca o DELETE do
 * JpaRepository por um UPDATE. Ver SoftDeletable.
 *
 * Detalhe que só aparece aqui: como o Post é reidratado pelo Axon a partir dos eventos, e não do banco,
 * um post apagado continua sendo carregável por um command — que é exatamente o que torna o
 * RestorePost possível.
 */
@SQLRestriction(Post.ALIVE)
@SQLDelete(sql = "update posts set deleted_at = current_timestamp where id = ?")
public class Post implements SoftDeletable {

    /** Predicado de "não apagado", em SQL. */
    public static final String ALIVE = SoftDeletion.COLUMN + " is null";

    /** Chave da tag no event store; tem de bater com o nome do campo {@code @EventTag} dos eventos. */
    public static final String TAG_KEY = "postId";

    @EmbeddedId
    @AttributeOverride(name = "value", column = @Column(name = "id", length = 36, nullable = false))
    private PostId id;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "title", length = 200, nullable = false))
    private PostTitle title;

    // TEXT em vez de @Lob: no Postgres o @Lob viraria um large object com OID à parte, e o corpo de um
    // post é texto comum. TEXT é o tipo certo e não tem limite prático
    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "content", columnDefinition = "TEXT", nullable = false))
    private PostContent content;

    /**
     * Quem escreveu: a entidade {@link Author}, não uma cópia do nome.
     * <p>
     * <b>{@code EAGER}, ao contrário das tags.</b> Toda {@code PostView} mostra o autor, e o mapeamento
     * para a view acontece <i>fora</i> da transação que leu o post — com {@code LAZY} o proxy estouraria
     * em {@code LazyInitializationException} na borda. Sendo obrigatório e único, o custo é um join, não
     * uma coleção; as consultas de lista ainda pedem o join explicitamente
     * ({@code @EntityGraph}/{@code join fetch}) para não virarem um SELECT por autor.
     * <p>
     * Sem cascade, pela mesma razão das tags: o Post escreve {@code posts.author_id} e nada mais. A
     * referência que o replay monta ({@link Author#reference}) jamais chega às tabelas {@code users} ou
     * {@code authors}.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private Author author;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** O estado que o mixin {@link SoftDeletable} pede. Nasce vazio: todo post nasce vivo. */
    @Embedded
    private SoftDeletion softDeletion = new SoftDeletion();

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "version", nullable = false))
    private PostVersion version;

    /**
     * As tags do post: a própria entidade {@link Tag}, ligada por {@code post_tags}.
     *
     * <h3>Sem cascade nenhum, e é isso que mantém a fronteira de pé</h3>
     * O Post escreve <b>só as linhas de {@code post_tags}</b>; a tabela {@code tags} é intocável a partir
     * daqui. Sem {@code cascade}, o {@code merge} de um Post resolve cada elemento pelo id e grava a
     * linha de ligação — nunca um INSERT ou UPDATE em {@code tags}. Cada agregado continua dono do seu
     * stream: a Tag não tem evento sobre posts, e o vínculo é sempre um {@code PostUpdatedEvent}.
     * <p>
     * O preço, honesto: assinalar uma tag inexistente agora viola a foreign key em vez de gravar uma
     * referência solta. Por isso {@code AssignTagToPostCommand} carrega a Tag antes de decidir.
     *
     * <h3>Carregada ou referência</h3>
     * Um elemento pode ser uma Tag carregada do banco (caminho de leitura, e o command que assinala) ou
     * uma referência montada pelo replay ({@link Tag#reference}), que só tem id e nome. As duas são o
     * mesmo elemento do {@code Set} porque {@code Tag.equals} é por id.
     *
     * <h3>Por que LAZY</h3>
     * No caminho de leitura ninguém navega por aqui. Quem serve as tags ao GraphQL é um DataLoader, que
     * as busca em lote para todos os posts da resposta numa consulta só — com {@code EAGER} o Hibernate
     * faria um SELECT por post e o lote não teria o que evitar.
     * <p>
     * No caminho de escrita a coleção sempre está carregada, porque a entidade que o command salva vem
     * reconstituída dos eventos pelo Axon, não do banco.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "post_tags",
            joinColumns = @JoinColumn(name = "post_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new LinkedHashSet<>();

    /** Exigido pelo JPA. Nenhum código de aplicação constrói um Post por aqui. */
    protected Post() {
    }

    // ---- decidir: dispara o evento e devolve o estado resultante --------------------------------

    /**
     * Construtor nomeado do Post: valida os dados, <b>dispara</b> {@link PostCreatedEvent} e devolve o
     * Post já criado, pronto para ser salvo por quem chamou. Nasce sem tags.
     *
     * <h3>Recebe um id, não um {@code Author} carregado</h3>
     * O agregado {@code Post} referencia o {@code Author} por <b>identidade</b>, e nunca precisou de mais
     * do que isso: ele compara ids e grava {@code posts.author_id}. Exigir a entidade carregada obrigava o
     * command handler a ler o agregado {@code User} antes de decidir — um agregado consultando outro, que
     * é justamente o que a fronteira existe para evitar.
     * <p>
     * Quem garante que o id existe e é de um autor é a chave estrangeira {@code fk_posts_author}, que
     * aponta para {@code authors} e não para {@code users}. E ela garante <b>melhor</b> do que a consulta
     * garantia: um SELECT antes do INSERT tem uma janela em que o autor pode ser apagado: a FK não tem.
     *
     * @throws InvalidPostException se title, content ou author violarem suas invariantes
     */
    public static Post create(PostId id,
                              String title,
                              String content,
                              Author author,
                              Instant now,
                              DomainEventPublisher events) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        PostCreatedEvent event = new PostCreatedEvent(
                id,
                PostTitle.of(title).value(),
                PostContent.of(content).value(),
                author.id(),
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
    public Post update(String newTitle, String newContent, Author actingAuthor,
                       Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");
        assertWrittenBy(actingAuthor);

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
    public Post assignTag(Tag tag, Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        if (hasTag(tag.id())) {
            throw new InvalidPostException("post já tem a tag " + tag.name());
        }

        Set<Tag> resulting = new LinkedHashSet<>(this.tags);
        resulting.add(tag);
        return raiseUpdate(this.title, this.content, resulting, now, events);
    }

    /**
     * Apaga o post e dispara {@link PostDeletedEvent}.
     * <p>
     * Sobrecarga do {@code delete(Instant)} que o mixin dá: aquele muda o estado, este <b>registra o
     * fato</b>. Num agregado event-sourced só o segundo é utilizável de fora — mudar o estado sem evento
     * daria um post que some do banco e reaparece no replay.
     *
     * @throws dev.manuelantunes.axonposts.domain.shared.AlreadyDeletedException se já estiver apagado
     */
    public Post delete(Author actingAuthor, Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");
        assertWrittenBy(actingAuthor);

        // a guarda do mixin roda aqui, antes de existir evento: decidir vem antes de registrar
        delete(now);

        PostDeletedEvent event = new PostDeletedEvent(id, author.id(), version.next().value(), now);
        events.raise(event);
        on(event);
        return this;
    }

    /**
     * Restaura o post e dispara {@link PostRestoredEvent}.
     * <p>
     * O estado volta aqui, mas a <b>linha</b> continua escondida pelo {@code @SQLRestriction} — quem a
     * traz de volta é {@code PostRepository.restore(...)}. Ver {@code RestorePostCommand}.
     *
     * @throws dev.manuelantunes.axonposts.domain.shared.NotDeletedException se não estiver apagado
     */
    public Post restore(Author actingAuthor, Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");
        assertWrittenBy(actingAuthor);

        restore();

        PostRestoredEvent event = new PostRestoredEvent(id, author.id(), version.next().value(), now);
        events.raise(event);
        on(event);
        return this;
    }

    private Post raiseUpdate(PostTitle resultingTitle,
                             PostContent resultingContent,
                             Set<Tag> resultingTags,
                             Instant now,
                             DomainEventPublisher events) {
        PostUpdatedEvent event = new PostUpdatedEvent(
                id,
                resultingTitle.value(),
                resultingContent.value(),
                this.author.id(),
                resultingTags.stream().map(t -> new PostUpdatedEvent.Tag(t.id().value(), t.name().value())).toList(),
                this.version.next().value(),
                now
        );

        events.raise(event);
        on(event);
        return this;
    }

    public boolean hasTag(TagId tagId) {
        return tags.stream().anyMatch(tag -> tag.id().equals(tagId));
    }

    public boolean hasNoTags() {
        return tags.isEmpty();
    }

    /**
     * Este post é deste autor? Compara por identidade, então funciona igual com o {@link Author}
     * carregado do banco e com a referência que o replay monta.
     */
    public boolean isWrittenBy(UserId authorId) {
        return author.id().equals(authorId);
    }

    /**
     * A guarda de propriedade, no <b>domínio</b> e não no controller.
     *
     * <h3>Por que aqui</h3>
     * O {@code @PreAuthorize("hasRole('AUTHOR')")} responde "esta pessoa pode escrever posts?". Esta
     * pergunta é outra: "pode escrever <b>neste</b> post?" — e a resposta depende do estado do agregado,
     * não de uma claim. Regra que depende do estado é invariante, e invariante mora no domínio: assim ela
     * vale venha o command de um controller GraphQL, de um consumidor de mensagem ou de um script.
     *
     * <h3>Por que recebe {@link Author} e não {@code UserId}</h3>
     * Porque o tipo já diz metade da regra. Um {@code UserId} é o id de <b>qualquer</b> usuário — um
     * leitor cabe na assinatura, e só o corpo do método descobriria. Um {@code Author} não: quem não é
     * autor não chega até aqui, e isso é conferido pelo compilador.
     * <p>
     * A comparação continua sendo por identidade ({@code isWrittenBy}), então funciona igual com o autor
     * carregado do banco e com a referência que o replay monta — as duas são o mesmo {@code Author} para
     * {@code equals}.
     *
     * @throws NotThePostAuthorException se o post for de outro autor
     */
    private void assertWrittenBy(Author actingAuthor) {
        Objects.requireNonNull(actingAuthor, "actingAuthor");
        if (!isWrittenBy(actingAuthor.id())) {
            throw new NotThePostAuthorException(id, actingAuthor.id());
        }
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
        this.author = Author.reference(event.authorId());
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

        // identity map do agregado: uma Tag que já está no Set (carregada do banco, com createdAt)
        // sobrevive; só um id desconhecido vira referência. Sem isto, decidir e logo aplicar o evento
        // rebaixaria a Tag carregada pelo command a uma referência do replay antes do save.
        Map<TagId, Tag> current = new LinkedHashMap<>();
        this.tags.forEach(tag -> current.put(tag.id(), tag));

        Set<Tag> next = new LinkedHashSet<>();
        event.tags().forEach(tag -> {
            TagId tagId = TagId.of(tag.tagId());
            Tag known = current.get(tagId);
            next.add(known != null ? known : Tag.reference(tagId, TagName.of(tag.name())));
        });

        this.tags.clear();
        this.tags.addAll(next);
    }

    /**
     * Aplica a exclusão. Usa {@code applyDeletion} e não {@code delete}: este método roda duas vezes para
     * o mesmo evento (o domínio ao decidir, o Axon ao apendar), e a versão guardada é a do evento — a
     * mesma idempotência do {@link #on(PostUpdatedEvent)}.
     */
    @EventSourcingHandler
    public void on(PostDeletedEvent event) {
        applyDeletion(event.occurredAt());
        this.updatedAt = event.occurredAt();
        this.version = new PostVersion(event.version());
    }

    /** A contraparte, pelo mesmo motivo. */
    @EventSourcingHandler
    public void on(PostRestoredEvent event) {
        applyRestoration();
        this.updatedAt = event.occurredAt();
        this.version = new PostVersion(event.version());
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

    public List<Tag> tags() {
        return List.copyOf(tags);
    }

    // ---- o que o mixin pede ---------------------------------------------------------------------

    /**
     * A guarda contra o {@code null} do Hibernate: quando <b>todas</b> as colunas de um {@code @Embedded}
     * vêm nulas — que é o caso de toda entidade viva, já que {@code deleted_at} é a única — ele deixa o
     * componente inteiro nulo em vez de instanciar um vazio. Sem isto, {@code isDeleted()} estouraria em
     * qualquer entidade lida do banco.
     * <p>
     * O inicializador do campo cobre as instâncias construídas em Java; esta linha cobre as hidratadas
     * pelo ORM. As duas são necessárias, e foi um teste contra o banco de verdade que mostrou a segunda.
     */
    @Override
    public SoftDeletion softDeletion() {
        if (softDeletion == null) {
            softDeletion = new SoftDeletion();
        }
        return softDeletion;
    }

    @Override
    public Object identity() {
        return id;
    }
}
