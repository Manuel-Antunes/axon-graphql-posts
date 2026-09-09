package dev.manuelantunes.axonposts.dto.controller;

/**
 * DTO de saída de um usuário: o {@code interface User} do schema.
 * <p>
 * <b>Selada</b>, e as duas permitidas correspondem exatamente aos dois tipos concretos do schema —
 * {@link ReaderView} → {@code Reader}, {@link AuthorView} → {@code Author}. É o que faz o
 * {@code ClassNameTypeResolver} conseguir dizer ao graphql-java qual tipo devolver num
 * {@code ... on Author}, e o que faz o compilador reclamar se um terceiro tipo aparecer no schema sem
 * par aqui.
 * <p>
 * O {@code email} <b>não</b> está aqui: ele é resolvido à parte, em lote, pelo
 * {@code UserEmailController}. Um {@code AuthorView} montado a partir do payload de um evento não teria
 * o e-mail para dar, e um campo que às vezes vem nulo é pior que um campo resolvido sob demanda.
 */
public sealed interface UserView permits ReaderView, AuthorView {

    String id();

    String name();
}
