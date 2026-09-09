package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.shared.SoftDeletable;
import dev.manuelantunes.axonposts.domain.shared.SoftDeletion;
import dev.manuelantunes.axonposts.domain.user.vo.DisplayName;
import dev.manuelantunes.axonposts.domain.user.exception.InvalidUserException;
import dev.manuelantunes.axonposts.domain.user.vo.AccountId;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.PasswordHash;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.FetchType;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * O usuário: quem tem credencial e consegue se autenticar. A raiz da hierarquia.
 *
 * <h2>Table per type ({@code JOINED}), e por que não as outras duas</h2>
 * {@code users} guarda o que todo usuário tem; {@code authors} guarda o que só um autor tem, com a
 * <b>mesma</b> chave primária. Um {@code Author} é uma linha em cada tabela, ligadas pelo id.
 * <ul>
 *   <li>{@code SINGLE_TABLE} poria tudo numa tabela só e exigiria que toda coluna de subclasse fosse
 *       nullable — o banco deixaria de conseguir dizer "todo autor tem bio";</li>
 *   <li>{@code TABLE_PER_CLASS} duplicaria as colunas de {@code User} em cada subclasse e quebraria a
 *       chave estrangeira: {@code posts.author_id} não teria uma tabela única para apontar;</li>
 *   <li>{@code JOINED} paga um join ao carregar um {@code Author} e é o único que mantém as duas
 *       coisas — não-nulo por subclasse e uma tabela de identidade só.</li>
 * </ul>
 * O preço é o join, e ele é real: {@code select ... from users u left join authors a on u.id = a.id}
 * em toda leitura polimórfica. Com dois níveis e este volume, é o negócio certo.
 *
 * <h2>Identidade aqui, credencial na {@link Account}</h2>
 * Esta classe responde "quem é" — id, e-mail, nome, papel. <b>Como</b> a pessoa prova que é ela mora nas
 * {@link Account}s, uma por provedor. É o desenho do better-auth, e é o que o account linking exige:
 * ligar o Google a uma conta que já entra pelo Keycloak é inserir uma linha em {@code accounts}, não
 * acrescentar coluna em {@code users}.
 * <p>
 * O {@code Authenticatable} morava aqui até a migração para o Keycloak e foi para a {@code Account} sem
 * que <b>esta classe mudasse uma linha</b> por causa disso — a vantagem inteira de a responsabilidade
 * estar numa interface e não no corpo da classe.
 * <p>
 * O que ficou foi o {@link SoftDeletable}. E {@code Author} herda o mixin de graça, sem uma linha: é a
 * diferença entre mixin e herança — a hierarquia continua livre para significar o que significa.
 *
 * <h2>Não é event-sourced, e isso é deliberado</h2>
 * {@code Post} e {@code Tag} são agregados com stream próprio; {@code User} não. Autenticação é estado
 * corrente — o que importa é a credencial de agora, não a história dela. Um usuário event-sourced
 * significaria reidratar um stream a cada requisição autenticada, para chegar exatamente à mesma linha
 * que um {@code select} devolve. Aqui o JPA é a fonte da verdade, e ponto.
 */
@Entity
@Table(name = "users")
@Inheritance(strategy = InheritanceType.JOINED)
/*
 * O par que liga o mixin ao banco:
 *
 * @SQLRestriction  — toda consulta de User (e de Author, pela herança) ganha "deleted_at is null".
 *                    Vale para as derived queries do Spring Data, para o findById e para as navegações;
 *                    ninguém precisa lembrar de filtrar.
 * @SQLDelete       — troca o DELETE por um UPDATE. É o que faz users.delete(x) do JpaRepository fazer a
 *                    coisa certa em vez de apagar de verdade.
 *
 * Ver o javadoc de SoftDeletable sobre por que restore() não pode passar por aqui.
 */
@SQLRestriction(User.ALIVE)
@SQLDelete(sql = "update users set deleted_at = current_timestamp where id = ?")
public class User implements SoftDeletable {

    /** Predicado de "não apagado", em SQL. Uma constante para os dois usos não divergirem. */
    public static final String ALIVE = SoftDeletion.COLUMN + " is null";

    @EmbeddedId
    @AttributeOverride(name = "value", column = @Column(name = "id", length = 36, nullable = false))
    private UserId id;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "email", length = Email.MAX_LENGTH, nullable = false, unique = true))
    private Email email;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "name", length = DisplayName.MAX_LENGTH, nullable = false))
    private DisplayName name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * As credenciais deste usuário, uma por provedor.
     * <p>
     * {@code cascade} + {@code orphanRemoval} porque {@code Account} é entidade <b>dentro</b> deste
     * agregado: nasce, vive e morre com o dono, e ninguém a manipula por fora. É a diferença para o
     * {@code Post.tags}, onde a {@code Tag} é agregado próprio e por isso não leva cascade nenhum.
     * <p>
     * <b>{@code LAZY}, e isso foi medido.</b> A intuição era {@code EAGER} — são poucas por usuário, e
     * quem carrega um usuário autenticado quer saber por onde ele entrou. Mas todo {@code join fetch
     * p.author} de uma consulta de posts também carrega o autor, e com {@code EAGER} o Hibernate honrava
     * a coleção com um SELECT <b>por autor</b> depois. Numa resposta com N autores, isso é um N+1 — o
     * mesmo que os DataLoaders desta aplicação existem para evitar, entrando pela porta dos fundos.
     * <p>
     * Foi o {@code BatchLoadingE2ETest} que pegou: ele compara o custo de uma resposta com um autor e com
     * dois, e a diferença não podia existir. Agora quem precisa das contas pede com {@code join fetch}
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

    protected User(UserId id, Email email, DisplayName name, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = Objects.requireNonNull(email, "email");
        this.name = Objects.requireNonNull(name, "name");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * Preenche só identidade e nome, para {@code Author.reference(...)}. Deixa credencial e
     * {@code createdAt} nulos de propósito: é o que marca a instância como referência, e é o que
     * {@link #isReference()} detecta.
     */
    protected void initReference(UserId id, DisplayName name) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
    }

    /** Um usuário comum: lê posts, não escreve. Nasce sem credencial — quem liga é {@link #link}. */
    public static User register(UserId id, String email, String name, Instant now) {
        return new User(id, Email.of(email), DisplayName.of(name), now);
    }

    // ---- comportamento --------------------------------------------------------------------------

    // ---- credenciais ----------------------------------------------------------------------------

    /**
     * Liga uma credencial a este usuário. <b>O único jeito de criar uma {@link Account}</b> — o
     * construtor dela é pacote-visível justamente para que nenhuma conta exista sem dono.
     * <p>
     * Ligar o mesmo provedor duas vezes é recusado: dois {@code sub} do Keycloak apontando para o mesmo
     * usuário local seria a maneira silenciosa de perder o rastro de qual é o bom.
     */
    public Account link(AuthProvider provider, String subject, PasswordHash passwordHash, Instant now) {
        if (isLinkedTo(provider)) {
            throw new InvalidUserException("usuário " + id + " já tem conta em " + provider);
        }
        Account account = new Account(AccountId.newId(), this, provider, subject, passwordHash, now);
        accounts.add(account);
        return account;
    }

    /** Atalho para o caso normal depois da migração: conta federada, sem senha local. */
    public Account link(AuthProvider provider, String subject, Instant now) {
        return link(provider, subject, null, now);
    }

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

    /**
     * As roles deste usuário, derivadas do tipo. Um {@link Author} sobrescreve para acrescentar
     * {@link Role#AUTHOR} — é o único lugar onde a hierarquia vira autorização.
     */
    public Set<Role> roles() {
        return EnumSet.of(Role.USER);
    }

    /** {@code true} se este usuário pode ser tratado como {@link Author}. */
    public boolean isAuthor() {
        return this instanceof Author;
    }

    /** {@code true} se esta instância é uma referência do replay, e não um usuário carregado. */
    public boolean isReference() {
        return createdAt == null;
    }

    // ---- estado ---------------------------------------------------------------------------------

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

    // ---- o que os mixins pedem ------------------------------------------------------------------

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

    /** Exigido por {@link SoftDeletable}: o que aparece na mensagem de erro. */
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
