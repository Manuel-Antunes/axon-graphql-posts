package dev.manuelantunes.axonposts.dto.controller;

import java.util.List;

/**
 * DTO de saída de um autor: o {@code type Author} do schema.
 * <p>
 * {@code bio} vem junto porque o Hibernate já a trouxe: carregar um {@code Author} numa herança
 * {@code JOINED} é um join com {@code authors}, e a coluna vem na mesma linha. Resolver esse campo à
 * parte era pagar uma consulta por um dado que já estava em memória.
 * <p>
 * {@code posts} continua sendo campo resolvido à parte — é uma coleção aberta e paginada, e aí a consulta
 * separada é o desenho certo, não desperdício.
 */
public record AuthorView(String id, String name, String email, String bio, List<AccountView> accounts)
        implements UserView {
}
