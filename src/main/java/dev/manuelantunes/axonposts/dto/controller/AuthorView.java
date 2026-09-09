package dev.manuelantunes.axonposts.dto.controller;

/**
 * DTO de saída de um autor: o {@code type Author} do schema.
 * <p>
 * Só id e nome — os dois campos que o {@code PostCreatedEvent} carrega, e é por isso que ele é
 * exatamente isto. Uma subscription monta a view a partir do evento, sem tocar no banco; se este record
 * tivesse mais campos, ou o evento cresceria ou o caminho da subscription precisaria de uma consulta.
 * <p>
 * {@code email} e {@code posts} são campos resolvidos à parte, cada um pelo seu controller.
 */
public record AuthorView(String id, String name) implements UserView {
}
