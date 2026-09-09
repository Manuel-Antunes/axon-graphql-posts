package dev.manuelantunes.axonposts.dto.controller;

import java.time.Instant;

/**
 * Resposta do {@code login}: o token e quem ele representa.
 * <p>
 * O {@code user} vem junto para que o cliente saiba, sem uma segunda ida ao servidor e sem decodificar o
 * JWT por conta própria, se está falando com um {@code Reader} ou com um {@code Author} — é a mesma
 * resolução de tipo do {@code me}.
 */
public record AuthPayload(String token, Instant expiresAt, UserView user) {
}
