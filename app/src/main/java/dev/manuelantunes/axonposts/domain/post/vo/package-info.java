/**
 * <b>Value objects</b> do Post: os tipos que carregam as invariantes do domínio.
 * <p>
 * Cada um valida e normaliza no construtor canônico, então um {@code PostTitle} que existe é sempre um
 * título válido — a entidade {@code Post} guarda o valor sem checar nada, e nenhum handler precisa
 * repetir a regra. Todos são {@code record}: imutáveis e com igualdade por valor.
 * <p>
 * Os value objects <b>não</b> aparecem nos eventos nem no read model: evento é contrato serializado e
 * read model é dado para leitura, ambos de primitivos. A conversão acontece nas fronteiras da entidade
 * ({@code Post.createdFrom(evento)} na entrada, {@code PostViewMapper} na saída).
 */
package dev.manuelantunes.axonposts.domain.post.vo;
