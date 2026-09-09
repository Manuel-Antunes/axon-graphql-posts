package dev.manuelantunes.axonposts.dto.controller;

import java.util.List;

/**
 * DTO de saída de um usuário que não escreve: o {@code type Reader} do schema.
 * <p>
 * O nome bate com a classe de domínio {@code Reader} — os dois nasceram junto, quando o {@code User}
 * virou raiz abstrata e "ser leitor" deixou de ser a ausência de linha em {@code authors}.
 */
public record ReaderView(String id, String name, String email, List<AccountView> accounts)
        implements UserView {
}
