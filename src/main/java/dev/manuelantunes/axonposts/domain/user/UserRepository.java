package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Porta do repositório de usuários. Devolve sempre o tipo <b>concreto</b> — um id de autor volta como
 * {@code Author}, e é isso que torna o {@code instanceof} do {@code CurrentUser} confiável: quem decide
 * o tipo é o banco, através da tabela {@code authors}, não uma flag no token.
 */
public interface UserRepository {

    void save(User user);

    Optional<User> findById(UserId userId);

    /**
     * O usuário dono de uma credencial. É <b>a</b> consulta do caminho autenticado: o token traz
     * {@code (provider, sub)} e é daqui que sai quem ele é.
     * <p>
     * Devolve o {@code User} e não a {@code Account} de propósito — {@code Account} é entidade dentro
     * deste agregado, e quem se carrega é a raiz.
     */
    Optional<User> findByAccount(AuthProvider provider, String subject);

    /**
     * Usada no account linking: um e-mail que já existe localmente e chega por um provedor novo é a
     * mesma pessoa, não uma segunda.
     */
    Optional<User> findByEmail(Email email);

    /**
     * Vários usuários de uma vez, para o {@code @BatchMapping} do campo {@code User.email}. Existe pelo
     * mesmo motivo do {@code findTagsByPostIds}: a alternativa é um SELECT por autor na resposta.
     */
    List<User> findAllById(Collection<UserId> userIds);

    /**
     * Torna a linha de um usuário apagado visível de novo. Mesma limitação do
     * {@code PostRepository.restore}: o {@code @SQLRestriction} esconde a linha até do SELECT que o
     * {@code merge} faz por dentro, então restaurar precisa de uma escrita que passe por fora do filtro.
     */
    void restore(UserId userId);

    /**
     * Transforma um {@code User} existente em {@code Author}, preservando id, e-mail e posts.
     *
     * <h3>Por que isto não é {@code save(new Author(...))}</h3>
     * Numa herança {@code JOINED} o tipo de uma linha é <b>onde ela existe</b>: quem tem linha em
     * {@code authors} é autor. O JPA não muda o tipo de uma entidade gerenciada — não há
     * {@code user.becomeAuthor()}, e recriar significaria apagar e reinserir, levando junto a chave
     * estrangeira dos posts.
     * <p>
     * A operação certa é inserir a linha filha que falta, e isso é um INSERT direto. É o preço de modelar
     * papel como subclasse, e ele só aparece quando quem manda no papel passa a ser outro sistema — aqui,
     * o Keycloak, onde a role pode ser concedida depois de o usuário já existir.
     */
    void promoteToAuthor(UserId userId, String bio);

    boolean isEmpty();
}
