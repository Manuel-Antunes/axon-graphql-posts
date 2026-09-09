package dev.manuelantunes.axonposts.exceptions;

import dev.manuelantunes.axonposts.domain.tag.exception.TagAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.user.exception.AccountAlreadyLinkedException;
import dev.manuelantunes.axonposts.domain.user.exception.EmailAlreadyInUseException;
import dev.manuelantunes.axonposts.domain.user.exception.NotAnAuthorException;
import dev.manuelantunes.axonposts.domain.user.Author;
import jakarta.persistence.EntityNotFoundException;
import org.hibernate.exception.ConstraintViolationException;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Violação de restrição do banco → exceção de domínio.
 *
 * <h2>Por que deixar o banco recusar</h2>
 * As restrições abaixo não são rede de segurança: são <b>a</b> garantia. Um SELECT antes do INSERT sempre
 * tem uma janela — a linha pode sumir entre a leitura e a escrita — e a restrição não tem. Validar antes
 * tranquiliza sem proteger, e ainda custa uma consulta por operação.
 * <p>
 * O que faltava para poder confiar nelas era isto: um erro de integridade não pode chegar ao cliente como
 * {@code INTERNAL_ERROR} com um stack trace de JDBC. Traduzido, ele vira a mesma exceção que uma checagem
 * na aplicação produziria — e o resto do sistema não precisa saber a diferença.
 *
 * <h2>O acoplamento, e como ele fica seguro</h2>
 * Isto casa por <b>nome de constraint</b>, então depende de a migration e este mapa concordarem. É um
 * acoplamento real, e a resposta a ele não é evitá-lo — é torná-lo verificável: o
 * {@code DataIntegrityTranslatorTest} pergunta ao Postgres se cada nome deste mapa existe de fato. Renomear
 * uma constraint no {@code V1} sem mexer aqui quebra o build.
 * <p>
 * Os nomes vêm do {@code V1__initial_schema.sql}, e é por isso que aquele arquivo foi curado à mão em vez
 * de ficar com os hashes que o Hibernate gera: {@code FKnjuop33mo69pd79ctplkck40n} não é algo em que se
 * possa ancorar tradução de erro.
 */
public final class DataIntegrityTranslator {

    /**
     * Constraint → o que ela significa para quem chamou.
     * <p>
     * As mensagens são deliberadamente <b>vagas sobre a causa</b>. A FK de autor dispara tanto para um id
     * inexistente quanto para um id de leitor, e distinguir os dois transformaria a recusa num oráculo de
     * quais usuários existem. A checagem que havia antes no {@code CreatePostCommand} tinha exatamente
     * esse defeito: um {@code UserNotFoundException} confirmava a ausência.
     */
    private static final Map<String, Supplier<RuntimeException>> BY_CONSTRAINT = Map.of(
            "fk_posts_author", NotAnAuthorException::new,
            "uk_accounts_provider_subject", AccountAlreadyLinkedException::new,
            "uk_users_email_active", EmailAlreadyInUseException::new,
            "uk_tags_name", TagAlreadyExistsException::new
    );

    private DataIntegrityTranslator() {
    }

    /**
     * Procura na cadeia de causas uma violação que este tradutor conheça.
     * <p>
     * Percorrer a cadeia é obrigatório: a violação nasce no driver, é embrulhada pelo Hibernate, depois
     * pelo Spring e depois pelo Axon — que a levanta no <b>commit</b> do {@code ProcessingContext}, e não
     * dentro do command handler. É por isso que a tradução mora aqui e não num {@code try/catch} no
     * handler: lá ela nunca chegaria.
     */
    public static Optional<RuntimeException> translate(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation) {
                RuntimeException translated = translated(violation);
                if (translated != null) {
                    return Optional.of(translated);
                }
            }
            if (t instanceof EntityNotFoundException missing && namesAuthor(missing)) {
                return Optional.of(new NotAnAuthorException());
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return Optional.empty();
    }

    /**
     * O caso que de fato acontece ao criar um post com {@code authorId} inválido.
     *
     * <h3>Por que não é a chave estrangeira que dispara</h3>
     * Porque o Hibernate chega antes. Ao resolver o {@code @ManyToOne} no {@code merge}, ele descobre que
     * não há linha em {@code authors} e lança {@code EntityNotFoundException} — o INSERT nem sai, e a
     * {@code fk_posts_author} fica como garantia final para quem escrever por fora do ORM.
     * <p>
     * A identificação é pelo <b>nome qualificado</b> da classe na mensagem, que é frágil por natureza:
     * uma mudança de formato do Hibernate passaria despercebida. É por isso que o
     * {@code DataIntegrityE2ETest} exercita este caminho contra um Postgres de verdade — se a mensagem
     * mudar, o teste cai.
     */
    private static boolean namesAuthor(EntityNotFoundException missing) {
        String message = missing.getMessage();
        return message != null && message.contains(Author.class.getName());
    }

    /** Nomes de constraint chegam com a caixa do banco; o Postgres cria em minúsculas. */
    private static RuntimeException translated(ConstraintViolationException violation) {
        String name = violation.getConstraintName();
        if (name == null) {
            return null;
        }
        Supplier<RuntimeException> mapped = BY_CONSTRAINT.get(name.toLowerCase());
        return mapped == null ? null : mapped.get();
    }

    /** Os nomes que este tradutor afirma existir no schema. Usado pelo teste que confere contra o banco. */
    public static java.util.Set<String> knownConstraints() {
        return BY_CONSTRAINT.keySet();
    }
}
