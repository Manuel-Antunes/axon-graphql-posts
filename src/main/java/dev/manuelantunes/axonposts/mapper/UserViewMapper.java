package dev.manuelantunes.axonposts.mapper;

import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.dto.controller.AuthorView;
import dev.manuelantunes.axonposts.dto.controller.ReaderView;
import dev.manuelantunes.axonposts.dto.controller.UserView;
import org.springframework.stereotype.Component;

/**
 * Domínio → saída do GraphQL, para a hierarquia de usuário.
 *
 * <h2>Por que este mapper é escrito à mão, e os outros não</h2>
 * O MapStruct resolve o destino em <b>tempo de compilação</b>: ele gera código para converter um tipo
 * declarado em outro tipo declarado. Aqui o destino depende do tipo <b>em runtime</b> — a mesma
 * referência {@code User} vira {@code ReaderView} ou {@code AuthorView} conforme o Hibernate tenha
 * instanciado um {@code User} ou um {@code Author}. Isso é despacho polimórfico, não mapeamento de
 * campos, e é exatamente o que um gerador não pode decidir por você.
 * <p>
 * O {@code instanceof} aqui não duplica o do {@code CurrentUser}: aquele <b>autoriza</b> (e falha se o
 * tipo não bater), este <b>apresenta</b> (e aceita os dois).
 */
@Component
public class UserViewMapper {

    /** Despacho pelo tipo concreto: é aqui que a herança JOINED vira o `... on Author` do schema. */
    public UserView toView(User user) {
        return user instanceof Author author ? toView(author) : new ReaderView(user.id().value(), user.name().value());
    }

    public AuthorView toView(Author author) {
        return new AuthorView(author.id().value(), author.name().value());
    }
}
