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
     * O id do usuário <b>apagado</b> dono desta credencial.
     * <p>
     * Existe porque o {@code @SQLRestriction} esconde os apagados de {@link #findByAccount}, e sem esta
     * consulta a próxima entrada da pessoa criaria um segundo usuário em vez de reativar o dela. Devolve
     * só o id: o agregado quem reidrata é o Axon, a partir do stream — que nenhum filtro SQL alcança.
     */
    Optional<UserId> findDeletedUserIdByAccount(AuthProvider provider, String subject);

    /**
     * O usuário <b>encerrado por promoção</b> com este e-mail, se houver.
     * <p>
     * Serve para retomar uma promoção interrompida: o {@code supersededBy} dele guarda o id do sucessor
     * que deveria ter sido criado, então dá para concluir a sequência em vez de deixar a pessoa sem conta.
     * Ver {@code UserProvisioning}.
     */
    Optional<User> findSupersededByEmail(Email email);

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

    boolean isEmpty();
}
