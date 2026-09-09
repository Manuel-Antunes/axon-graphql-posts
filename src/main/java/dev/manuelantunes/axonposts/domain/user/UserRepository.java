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

    /** Usada no login: o e-mail é a credencial de entrada. */
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
