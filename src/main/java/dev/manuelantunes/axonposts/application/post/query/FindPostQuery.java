package dev.manuelantunes.axonposts.application.post.query;

import org.axonframework.messaging.queryhandling.annotation.Query;

/**
 * Query: um Post pelo id.
 * <p>
 * O id vem como {@code String} e não como {@code PostId} de propósito: uma query não decide nada, então
 * não faz sentido rejeitar um id malformado com exceção de domínio — id que não existe simplesmente não
 * acha nada.
 */
@Query(namespace = "posts", name = "FindPost", version = "1.0.0")
public record FindPostQuery(String postId) {
}
