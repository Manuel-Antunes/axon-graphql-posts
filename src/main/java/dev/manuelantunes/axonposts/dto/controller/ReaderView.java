package dev.manuelantunes.axonposts.dto.controller;

/**
 * DTO de saída de um usuário que não escreve: o {@code type Reader} do schema.
 * <p>
 * O nome no schema é {@code Reader} e não {@code User} porque {@code User} já é a interface — GraphQL
 * não deixa um tipo e uma interface dividirem o nome. Do lado Java a hierarquia continua sendo
 * {@code User}/{@code Author}; a tradução dos dois nomes é feita uma vez, na configuração do
 * {@code ClassNameTypeResolver}.
 */
public record ReaderView(String id, String name) implements UserView {
}
