package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.concurrent.CompletableFuture;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Detém o {@code Emitter} e nada mais.
 *
 * <h2>Por que ele existe como bean separado</h2>
 * Por ordem de partida, não por organização. O {@link AxonChannelIntegration} é instanciado de dentro
 * do {@code AxonExtension.init} — um recorder de {@code RUNTIME_INIT} da extensão do Axon —, e nesse
 * instante o SmallRye Reactive Messaging <b>ainda não ligou os channels</b>. Injetar o {@code Emitter}
 * lá falha com {@code SRMSG00019: Unable to connect an emitter with the channel}, e falha igual em
 * campo ou em construtor: o problema é <i>quando</i>, não <i>como</i>.
 * <p>
 * Sendo {@code @ApplicationScoped}, o que o configurador recebe é um <b>client proxy</b>: a instância
 * real — e portanto a resolução do emitter — só acontece na primeira chamada de método, que é a
 * primeira publicação de evento, muito depois da partida.
 * <p>
 * É o mesmo padrão pelo qual o {@code LazyJpaEventStore} tentou resolver o problema gêmeo do Flyway.
 * Aqui funciona porque o adiamento é do CDI, não de uma lambda que o Axon resolve na própria partida.
 */
@ApplicationScoped
public class ChannelEventDispatcher {

    @Inject
    @Channel("axon-events")
    Emitter<AxonEventEnvelope> channel;

    /**
     * O overload de {@code Emitter} que aceita {@code Message} — o único por onde passa metadado, e
     * portanto endereçamento — devolve {@code void}. Os callbacks de ack/nack são a única forma de
     * esperar o envio nesse caminho.
     */
    public CompletableFuture<Void> send(AxonEventEnvelope envelope, Metadata addressing) {
        CompletableFuture<Void> sent = new CompletableFuture<>();
        channel.send(Message.of(envelope, addressing,
                () -> {
                    sent.complete(null);
                    return CompletableFuture.completedFuture(null);
                },
                failure -> {
                    sent.completeExceptionally(failure);
                    return CompletableFuture.completedFuture(null);
                }));
        return sent;
    }
}
