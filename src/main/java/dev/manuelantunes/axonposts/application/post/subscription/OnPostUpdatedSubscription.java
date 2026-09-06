package dev.manuelantunes.axonposts.application.post.subscription;

import org.axonframework.messaging.queryhandling.annotation.Query;

/**
 * Subscription query: "me avise quando um Post for atualizado".
 *
 * @param postId tópico opcional — {@code null} recebe update de qualquer Post; preenchido, só os daquele
 *               id. O filtro é avaliado no emit, pelo {@code PostUpdatedEventHandler}.
 */
@Query(namespace = "posts", name = "OnPostUpdated", version = "1.0.0")
public record OnPostUpdatedSubscription(String postId) {

    /** O predicado do tópico mora junto da subscription: quem emite não precisa saber a regra. */
    public boolean matches(String updatedPostId) {
        return postId == null || postId.isBlank() || postId.equals(updatedPostId);
    }
}
