package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.user.vo.AccountId;
import dev.manuelantunes.axonposts.domain.user.vo.PasswordHash;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Uma credencial de um {@link User}: "esta pessoa é o {@code subject} tal no provedor tal".
 *
 * <h2>Por que separar de {@code User} (o modelo do better-auth)</h2>
 * Porque identidade e credencial mudam por razões diferentes e em números diferentes. Uma pessoa é
 * <b>um</b> usuário — nome, e-mail, posts, papel — e pode ter <b>várias</b> maneiras de provar que é ela:
 * senha, Keycloak, Google. Enfiar isso tudo em {@code users} obrigaria a uma coluna por provedor, todas
 * anuláveis, e a tabela cresceria em largura a cada integração nova.
 * <p>
 * Separado, ligar um provedor novo é <b>inserir uma linha</b>. É isso que o account linking exige, e é
 * por isso que o better-auth (e o NextAuth, e o Keycloak internamente) desenham assim.
 *
 * <h2>Entidade, não agregado</h2>
 * {@code Account} tem identidade própria, mas não vive sozinha: não faz sentido uma credencial sem dono,
 * e ninguém a busca a não ser passando pelo usuário. Ela é entidade <b>dentro</b> do agregado
 * {@code User} — que é o que justifica o {@code cascade}/{@code orphanRemoval} do outro lado e o
 * construtor ser pacote-visível: só {@link User#link} cria uma.
 * <p>
 * Contraste com {@code Tag}, que é agregado próprio: aquela existe sem post nenhum, tem stream de eventos
 * e é referenciada por identidade. A regra que separa as duas é a mesma de sempre — quem pode existir e
 * mudar sozinho é raiz; quem só faz sentido dentro de outro, não.
 *
 * <h2>A chave que importa é {@code (provider, subject)}</h2>
 * O índice único não é sobre o id: é sobre o par provedor+assunto. É ele que impede a mesma conta do
 * Keycloak de ser ligada a dois usuários locais, que seria a maneira mais silenciosa de dois usuários
 * virarem um.
 */
@Entity
@Table(
        name = "accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_accounts_provider_subject",
                columnNames = {"provider", "subject"})
)
public class Account implements Authenticatable {

    @EmbeddedId
    @AttributeOverride(name = "value", column = @Column(name = "id", length = 36, nullable = false))
    private AccountId id;

    /**
     * O dono. {@code LAZY} porque o caminho normal é o inverso — parte-se do usuário para as contas —
     * e carregar o usuário ao ler uma conta seria trabalho que ninguém pediu.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** {@code STRING} e não {@code ORDINAL}: reordenar o enum não pode reescrever o significado das linhas. */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 32, nullable = false)
    private AuthProvider provider;

    @Column(name = "subject", length = 255, nullable = false)
    private String subject;

    /** Nulo para conta federada. É o "algumas têm senha e outras não", na coluna. */
    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "password_hash"))
    private PasswordHash passwordHash;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    /** Exigido pelo JPA. */
    protected Account() {
    }

    /** Pacote-visível: quem cria uma conta é {@link User#link}, para o dono nunca ficar sem preencher. */
    Account(AccountId id, User user, AuthProvider provider, String subject,
            PasswordHash passwordHash, Instant linkedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.user = Objects.requireNonNull(user, "user");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.subject = Objects.requireNonNull(subject, "subject").strip();
        this.passwordHash = passwordHash;
        this.linkedAt = Objects.requireNonNull(linkedAt, "linkedAt");
    }

    // ---- o que o mixin pede ---------------------------------------------------------------------

    @Override
    public AuthProvider provider() {
        return provider;
    }

    @Override
    public String subject() {
        return subject;
    }

    @Override
    public Optional<PasswordHash> passwordHash() {
        return Optional.ofNullable(passwordHash);
    }

    // ---- estado ---------------------------------------------------------------------------------

    public AccountId id() {
        return id;
    }

    public User user() {
        return user;
    }

    public Instant linkedAt() {
        return linkedAt;
    }

    // ---- identidade -----------------------------------------------------------------------------

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Account account && id != null && id.equals(account.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return provider + ":" + subject;
    }
}
