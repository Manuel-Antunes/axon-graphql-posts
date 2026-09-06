package dev.manuelantunes.axonposts.application.post.subscription;

import org.axonframework.messaging.queryhandling.annotation.Query;

/**
 * Subscription query: "me avise de todo Post criado". Sem filtro — é um tópico global.
 * <p>
 * É o payload do {@code QueryMessage} que o {@code QueryBus} mantém registrado enquanto o {@code Flux}
 * estiver assinado; o event handler de {@code PostCreatedEvent} emite para ele por tipo
 * ({@code emitter.emit(OnPostCreatedSubscription.class, ...)}).
 */
@Query(namespace = "posts", name = "OnPostCreated", version = "1.0.0")
public record OnPostCreatedSubscription() {
}
