package dev.manuelantunes.axonposts.dto.controller;

import java.time.Instant;

/**
 * DTO de saída de uma credencial: o {@code type Account} do schema.
 * <p>
 * {@code hasPassword} é o campo que torna o account linking legível para um cliente: com ele a interface
 * sabe se deve oferecer "entrar com senha" ou só "entrar com o provedor". Vem do
 * {@code Authenticatable.hasPassword()} — o mixin, atravessando até a borda.
 * <p>
 * Nenhum segredo passa por aqui: o hash fica na entidade, e o que sai é só a resposta de sim ou não.
 */
public record AccountView(String provider, String subject, boolean hasPassword, Instant linkedAt) {
}
