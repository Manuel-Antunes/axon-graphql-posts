package dev.manuelantunes.axonposts.application.user.view;

import dev.manuelantunes.axonposts.domain.user.Account;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.User;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;

/**
 * Domínio → saída do GraphQL, para a hierarquia de usuário.
 *
 * <h2>Por que este mapper é escrito à mão, e os outros não</h2>
 * O MapStruct resolve o destino em <b>tempo de compilação</b>. Aqui o destino depende do tipo em
 * <b>runtime</b> — a mesma referência {@code User} vira {@code ReaderView} ou {@code AuthorView} conforme
 * o {@code @EntityCreator} do Axon (no caminho de escrita) ou o Hibernate (no de leitura) tenham
 * instanciado um {@code Reader} ou um {@code Author}. Isso é despacho polimórfico, não mapeamento de
 * campos, e é exatamente o que um gerador não pode decidir por você.
 * <p>
 * O {@code instanceof} aqui não duplica o do {@code CurrentUser}: aquele <b>autoriza</b> (e falha se o
 * tipo não bater), este <b>apresenta</b> (e aceita os dois).
 *
 * <h2>Uma passada, view completa</h2>
 * Tudo o que o schema pede sobre um usuário sai desta chamada — nome, e-mail, bio, contas. Nenhum campo
 * fica devendo uma consulta a mais, porque nenhum deles precisa de nada que a entidade carregada já não
 * tenha.
 */
@ApplicationScoped
public class UserViewMapper {

    /** Ordem estável: sem ela, a mesma resposta variaria entre execuções por causa do {@code Set}. */
    private static final Comparator<Account> BY_LINK_TIME =
            Comparator.comparing(Account::linkedAt).thenComparing(account -> account.id().value());

    /** Despacho pelo tipo concreto: é aqui que a hierarquia vira o `... on Author` do schema. */
    public UserView toView(User user) {
        return user instanceof Author author ? toView(author) : toReaderView(user);
    }

    public AuthorView toView(Author author) {
        return new AuthorView(
                author.id().value(),
                author.name().value(),
                author.email().value(),
                author.bio(),
                toAccountViews(author));
    }

    private ReaderView toReaderView(User user) {
        return new ReaderView(
                user.id().value(),
                user.name().value(),
                user.email().value(),
                toAccountViews(user));
    }

    private List<AccountView> toAccountViews(User user) {
        return user.accounts().stream()
                .sorted(BY_LINK_TIME)
                .map(account -> new AccountView(
                        account.provider(),
                        account.subject(),
                        account.hasPassword(),
                        account.linkedAt()))
                .toList();
    }
}
