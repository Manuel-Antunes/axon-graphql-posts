package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;
import dev.manuelantunes.axonposts.domain.shared.SoftDeletable;
import dev.manuelantunes.axonposts.domain.shared.SoftDeletion;
import dev.manuelantunes.axonposts.domain.user.event.AccountLinkedEvent;
import dev.manuelantunes.axonposts.domain.user.event.UserDeletedEvent;
import dev.manuelantunes.axonposts.domain.user.event.UserRegisteredEvent;
import dev.manuelantunes.axonposts.domain.user.event.UserRestoredEvent;
import dev.manuelantunes.axonposts.domain.user.event.UserSupersededEvent;
import dev.manuelantunes.axonposts.domain.user.exception.InvalidUserException;
import dev.manuelantunes.axonposts.domain.user.vo.AccountId;
import dev.manuelantunes.axonposts.domain.user.vo.DisplayName;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.PasswordHash;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * O usuário: quem tem identidade neste sistema. Raiz <b>abstrata</b> da hierarquia.
 *
 * <h2>Entidade polimórfica do Axon</h2>
 * {@code @EventSourced(concreteTypes = {Reader.class, Author.class})} declara os dois tipos concretos, e
 * o {@link #create(UserRegisteredEvent) @EntityCreator} escolhe entre eles lendo o <b>primeiro evento do
 * stream</b>. Não há coluna de discriminador, flag nem consulta decidindo isso: o tipo é uma leitura do
 * histórico.
 * <p>
 * É a mesma ideia que o {@code Post} já usa com um tipo só, agora com dois — e alinha as duas metades da
 * entidade: o Axon resolve a subclasse pelo evento, o Hibernate resolve pela tabela filha que existe.
 *
 * <h3>A regra que vem junto: o tipo é fixo</h3>
 * A documentação do Axon é explícita — <i>the concrete type is fixed at creation time; an entity cannot
 * change its type at runtime</i>. Não existe evento que transforme um {@code Reader} em {@code Author},
 * porque o {@code @EntityCreator} já decidiu ao ler o primeiro evento e todo replay decidiria igual.
 * <p>
 * Promover, então, é <b>encerrar um agregado e abrir outro</b>: {@link #supersede} fecha o do leitor e um
 * {@code UserRegisteredEvent} com {@code supersedes} preenchido abre o do autor. Ver
 * {@link UserSupersededEvent} sobre por que isso não perde nada.
 *
 * <h2>Table per type ({@code JOINED})</h2>
 * {@code users} guarda o que todo usuário tem; {@code readers} e {@code authors} guardam o que é próprio
 * de cada um, com a <b>mesma</b> chave primária.
 * <ul>
 *   <li>{@code SINGLE_TABLE} exigiria que toda coluna de subclasse fosse nullable — o banco deixaria de
 *       conseguir dizer "todo autor tem bio";</li>
 *   <li>{@code TABLE_PER_CLASS} duplicaria as colunas de {@code User} e quebraria a chave estrangeira:
 *       {@code posts.author_id} não teria uma tabela única para apontar;</li>
 *   <li>{@code JOINED} paga um join e é o único que mantém as duas coisas.</li>
 * </ul>
 * A tabela {@code readers} tem só o id, e é de propósito: com a raiz abstrata, "ser leitor" precisa de um
 * lugar onde ser verdade. Antes, leitor era a <i>ausência</i> de linha em {@code authors} — uma definição
 * por negação que não sobrevive a uma terceira subclasse.
 *
 * <h2>Identidade aqui, credencial na {@link Account}</h2>
 * Esta classe responde "quem é". <b>Como</b> a pessoa prova que é ela mora nas {@link Account}s, uma por
 * provedor — o desenho do better-auth, e o que o account linking exige.
 */
@Entity
@Table(name = "users")
@Inheritance(strategy = InheritanceType.JOINED)
@EventSourced(tagKey = User.TAG_KEY, idType = UserId.class, concreteTypes = {Reader.class, Author.class})
@SQLRestriction(User.ALIVE)
@SQLDelete(sql = "update users set deleted_at = current_timestamp where id = ?")
public abstract class User implements SoftDeletable {

    /** Chave da tag no event store; tem de bater com o {@code @EventTag} dos eventos de usuário. */
    public static final String TAG_KEY = "userId";

    /** Predicado de "não apagado", em SQL. */
    public static final String ALIVE = SoftDeletion.COLUMN + " is null";

    @EmbeddedId
    @AttributeOverride(name = "value", column = @Column(name = "id", length = 36, nullable = false))
    private UserId id;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "email", length = Email.MAX_LENGTH, nullable = false))
    private Email email;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "name", length = DisplayName.MAX_LENGTH, nullable = false))
    private DisplayName name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Preenchido quando este usuário foi promovido: aponta para o agregado que o substituiu. */
    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "superseded_by", length = 36))
    private UserId supersededBy;

    /** O usuário que este substitui, se nasceu de uma promoção. O caminho inverso de {@link #supersededBy}. */
    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "supersedes", length = 36))
    private UserId supersedes;

    /**
     * As credenciais deste usuário, uma por provedor.
     * <p>
     * {@code cascade} + {@code orphanRemoval} porque {@code Account} é entidade <b>dentro</b> deste
     * agregado: nasce, vive e morre com o dono. É a diferença para o {@code Post.tags}, onde a
     * {@code Tag} é agregado próprio e por isso não leva cascade nenhum.
     * <p>
     * <b>{@code LAZY}, e isso foi medido.</b> Com {@code EAGER}, todo {@code join fetch p.author} de uma
     * consulta de posts carregava o autor e o Hibernate honrava a coleção com um SELECT <b>por autor</b>
     * depois — um N+1 numa resposta com N autores. Quem precisa das contas pede com {@code join fetch}
     * (ver {@code SpringDataUserRepository}); quem só precisa do autor de um post não paga por elas.
     */
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<Account> accounts = new LinkedHashSet<>();

    /** O estado que o mixin {@link SoftDeletable} pede. Nasce vazio: todo usuário nasce vivo. */
    @Embedded
    private SoftDeletion softDeletion = new SoftDeletion();

    /** Exigido pelo JPA. */
    protected User() {
    }

    /**
     * Construtor que as subclasses chamam ao serem criadas pelo {@link #create}.
     * <p>
     * {@code protected} porque a documentação do Axon avisa: campos do pai lidos ou atribuídos por
     * construtor de subclasse precisam ser ao menos protegidos. Aqui os campos continuam privados e é o
     * <b>construtor</b> que é protegido — a subclasse não toca em campo do pai, delega.
     */
    protected User(UserRegisteredEvent event) {
        this.id = Objects.requireNonNull(event.userId(), "userId");
        this.email = Email.of(event.email());
        this.name = DisplayName.of(event.name());
        this.createdAt = event.occurredAt();
        this.supersedes = event.supersedes();
    }

    // ---- decidir --------------------------------------------------------------------------------

    /**
     * Construtor nomeado: valida, dispara {@link UserRegisteredEvent} e devolve o usuário já do tipo
     * certo. É o único caminho para um usuário nascer.
     */
    public static User register(UserId id, String email, String name, boolean author, String bio,
                                UserId supersedes, Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        // valida antes de disparar: um evento no store é definitivo, e não se apaga um fato inválido
        UserRegisteredEvent event = new UserRegisteredEvent(
                id, Email.of(email).value(), DisplayName.of(name).value(), author, bio, supersedes, now);

        events.raise(event);
        return create(event);
    }

    /**
     * Liga uma credencial. Dispara {@link AccountLinkedEvent} e devolve a {@link Account} resultante.
     * <p>
     * Ligar o mesmo provedor duas vezes é recusado: dois {@code sub} do Keycloak apontando para o mesmo
     * usuário local seria a maneira silenciosa de perder o rastro de qual é o bom.
     */
    public Account link(AuthProvider provider, String subject, PasswordHash passwordHash,
                        Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(events, "events");
        if (isLinkedTo(provider)) {
            throw new InvalidUserException("usuário " + id + " já tem conta em " + provider);
        }

        AccountLinkedEvent event = new AccountLinkedEvent(
                id, AccountId.newId().value(), provider, subject,
                passwordHash == null ? null : passwordHash.value(), now);

        events.raise(event);
        on(event);
        return accountFor(provider).orElseThrow();
    }

    /** Atalho para o caso normal: conta federada, sem senha local. */
    public Account link(AuthProvider provider, String subject, Instant now, DomainEventPublisher events) {
        return link(provider, subject, null, now, events);
    }

    /**
     * Encerra este agregado em favor de outro — o passo do leitor numa promoção.
     *
     * @throws InvalidUserException se já tiver sido substituído, ou se for um autor (promover autor não
     *                              existe: ele já é o topo, e teria posts para remapear)
     */
    public User supersede(UserId successor, Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(successor, "successor");
        Objects.requireNonNull(events, "events");
        if (isAuthor()) {
            throw new InvalidUserException("um autor não é substituído: " + id);
        }
        if (isSuperseded()) {
            throw new InvalidUserException("usuário " + id + " já foi substituído por " + supersededBy);
        }

        UserSupersededEvent event = new UserSupersededEvent(id, successor, now);
        events.raise(event);
        on(event);
        return this;
    }

    /**
     * Apaga a conta e dispara {@link UserDeletedEvent}.
     * <p>
     * Sobrecarga do {@code delete(Instant)} que o mixin dá: aquele muda o estado, este <b>registra o
     * fato</b>. Num agregado event-sourced só o segundo é utilizável de fora — mudar o estado sem evento
     * daria um usuário que some do banco e reaparece no replay.
     *
     * @throws dev.manuelantunes.axonposts.domain.shared.AlreadyDeletedException se já estiver apagado
     */
    public User delete(Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        // a guarda do mixin roda aqui, antes de existir evento: decidir vem antes de registrar
        delete(now);

        UserDeletedEvent event = new UserDeletedEvent(id, now);
        events.raise(event);
        on(event);
        return this;
    }

    /**
     * Reativa a conta e dispara {@link UserRestoredEvent}.
     * <p>
     * O estado volta aqui, mas a <b>linha</b> continua escondida pelo {@code @SQLRestriction} — quem a
     * traz de volta é {@code UserRepository.restore(...)}. É a mesma divisão do {@code RestorePostCommand}:
     * o domínio decide, o adapter sabe como gravar.
     *
     * @throws dev.manuelantunes.axonposts.domain.shared.NotDeletedException se não estiver apagado
     */
    public User restore(Instant now, DomainEventPublisher events) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(events, "events");

        restore();

        UserRestoredEvent event = new UserRestoredEvent(id, now);
        events.raise(event);
        on(event);
        return this;
    }

    // ---- evoluir: reconstituição a partir do stream ---------------------------------------------

    /**
     * <b>O ponto do polimorfismo.</b> O Axon chama com o primeiro evento do stream e é esta linha que
     * decide a classe — {@code Author} ou {@code Reader}.
     * <p>
     * Repare que não há {@code if} sobre o banco, sobre a role do token ou sobre o que quer que seja fora
     * do evento: o tipo é função pura do histórico, e por isso todo replay chega ao mesmo lugar.
     */
    @EntityCreator
    public static User create(UserRegisteredEvent event) {
        return event.author() ? new Author(event) : new Reader(event);
    }

    /**
     * Aplica uma credencial nova. <b>Idempotente</b> pelo {@code accountId}: o mesmo evento chega duas
     * vezes — o domínio aplica ao decidir e o Axon aplica ao apendar — e a segunda não pode duplicar a
     * conta.
     */
    @EventSourcingHandler
    public void on(AccountLinkedEvent event) {
        AccountId accountId = AccountId.of(event.accountId());
        if (accounts.stream().anyMatch(account -> account.id().equals(accountId))) {
            return;
        }
        accounts.add(new Account(
                accountId, this, event.provider(), event.subject(),
                event.passwordHash() == null ? null : PasswordHash.of(event.passwordHash()),
                event.occurredAt()));
    }

    /**
     * Encerra o agregado <b>e solta as credenciais</b>.
     * <p>
     * Limpar as contas não é faxina: o índice único de {@code (provider, subject)} impede que a mesma
     * conta do Keycloak exista em dois usuários, então o agregado novo só consegue religá-la depois que
     * este a soltar. E é o que o modelo diz mesmo — um usuário substituído não tem por onde entrar.
     * <p>
     * O {@code orphanRemoval} do lado do JPA transforma esse {@code clear()} em DELETE ao salvar.
     * <p>
     * Idempotente como os outros: recebe o valor absoluto, não um incremento, e limpar duas vezes uma
     * coleção já vazia não muda nada.
     */
    @EventSourcingHandler
    public void on(UserSupersededEvent event) {
        this.supersededBy = event.supersededBy();
        this.accounts.clear();
    }

    /**
     * Usa {@code applyDeletion} e não {@code delete}: este método roda duas vezes para o mesmo evento (o
     * domínio aplica ao decidir, o Axon aplica ao apendar), e a versão guardada é a do evento.
     */
    @EventSourcingHandler
    public void on(UserDeletedEvent event) {
        applyDeletion(event.occurredAt());
    }

    /** A contraparte, pelo mesmo motivo. */
    @EventSourcingHandler
    public void on(UserRestoredEvent event) {
        applyRestoration();
    }

    // ---- credenciais ----------------------------------------------------------------------------

    public Optional<Account> accountFor(AuthProvider provider) {
        return accounts.stream().filter(account -> account.provider() == provider).findFirst();
    }

    public boolean isLinkedTo(AuthProvider provider) {
        return accountFor(provider).isPresent();
    }

    /** Cópia defensiva: quem quiser mexer nas contas passa por {@link #link}. */
    public Set<Account> accounts() {
        return Set.copyOf(accounts);
    }

    // ---- estado ---------------------------------------------------------------------------------

    /**
     * As roles deste usuário, derivadas do tipo. {@link Author} sobrescreve para acrescentar
     * {@link Role#AUTHOR} — é o único lugar onde a hierarquia vira autorização.
     */
    public Set<Role> roles() {
        return EnumSet.of(Role.USER);
    }

    /** {@code true} se este usuário pode ser tratado como {@link Author}. */
    public boolean isAuthor() {
        return this instanceof Author;
    }

    /** {@code true} se foi promovido: existe outro agregado no lugar deste. */
    public boolean isSuperseded() {
        return supersededBy != null;
    }

    public UserId supersededBy() {
        return supersededBy;
    }

    public UserId supersedes() {
        return supersedes;
    }

    /** {@code true} se esta instância é uma referência do replay, e não um usuário carregado. */
    public boolean isReference() {
        return createdAt == null;
    }

    public UserId id() {
        return id;
    }

    public Email email() {
        return email;
    }

    public DisplayName name() {
        return name;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /**
     * Preenche só a identidade, para {@code Author.reference(...)}. Deixa todo o resto nulo de propósito:
     * {@code createdAt} nulo é o que marca a instância como referência, e o que {@link #isReference()}
     * detecta.
     */
    protected void initReference(UserId id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    // ---- o que os mixins pedem ------------------------------------------------------------------

    /**
     * A guarda contra o {@code null} do Hibernate: quando <b>todas</b> as colunas de um {@code @Embedded}
     * vêm nulas — que é o caso de toda entidade viva, já que {@code deleted_at} é a única — ele deixa o
     * componente inteiro nulo em vez de instanciar um vazio.
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

    // ---- identidade -----------------------------------------------------------------------------

    /**
     * Por id, e {@code instanceof User} em vez de {@code getClass()}: numa hierarquia com proxy do
     * Hibernate, um {@code Author} carregado preguiçosamente é uma subclasse gerada, e comparar classes
     * diria que ele não é igual a ele mesmo.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof User user && id != null && id.equals(user.id);
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
