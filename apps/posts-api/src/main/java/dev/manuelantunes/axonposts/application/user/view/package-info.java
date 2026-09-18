/**
 * <b>O que as queries de usuário devolvem</b>, e o único ponto polimórfico do schema.
 * <p>
 * {@code UserView} é interface porque a hierarquia do domínio é: quem sai do banco com linha em
 * {@code authors} vira {@code AuthorView}, quem não tem vira {@code ReaderView}. O {@code UserViewMapper}
 * é o único mapper escrito à mão do projeto — o destino depende do tipo em runtime, que é exatamente o que
 * o MapStruct não resolve.
 * <p>
 * Mesma razão de {@code application.post.view} para estar na aplicação: {@code FindUsersByIds} devolve um
 * mapa destes tipos pelo query bus, e o campo {@code Post.author} os consome em lote.
 */
package dev.manuelantunes.axonposts.application.user.view;
