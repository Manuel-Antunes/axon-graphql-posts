package dev.manuelantunes.axonposts.dto.controller;

import java.util.List;

/**
 * DTO de saída de um usuário: o {@code interface User} do schema.
 * <p>
 * <b>Selada</b>, e as duas permitidas correspondem exatamente aos dois tipos concretos do schema —
 * {@link ReaderView} → {@code Reader}, {@link AuthorView} → {@code Author}, os mesmos dois que o
 * {@code @EventSourced(concreteTypes = ...)} declara no domínio. O compilador reclama se um terceiro tipo
 * aparecer no schema sem par aqui.
 *
 * <h2>Ele carrega tudo agora, e isso eliminou consultas</h2>
 * Antes tinha só id e nome, e {@code email}, {@code bio} e {@code accounts} eram três
 * {@code @BatchMapping} separados, cada um voltando ao banco para preencher um campo — inclusive quando
 * quem montou a view já tinha o usuário inteiro carregado.
 * <p>
 * A view completa é montada de uma vez, a partir do que o Hibernate já hidratou (a herança {@code JOINED}
 * traz a bio no mesmo join; o {@code join fetch} traz as contas). Sobrou <b>uma</b> consulta em lote onde
 * havia três.
 */
public sealed interface UserView permits ReaderView, AuthorView {

    String id();

    String name();

    String email();

    /** As credenciais ligadas — o account linking, visível. */
    List<AccountView> accounts();
}
