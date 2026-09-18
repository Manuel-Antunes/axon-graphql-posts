package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.List;

import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.meks.quarkiverse.axon.runtime.customizations.EventDispatchInterceptorsProducer;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A ligação: um {@code MessageDispatchInterceptor<EventMessage>} — o ponto de extensão do <b>próprio
 * Axon</b> para "faça algo com todo evento despachado".
 *
 * <h2>Por que interceptador, e não decorador de componente</h2>
 * A primeira tentativa foi {@code registerDecorator(EventSink.class, ...)}, no mesmo mecanismo do
 * {@code TracingEventSink}. Não funciona, e o erro diz por quê:
 * <pre>
 * ClassCastException: Original component type [interface EventStore] is not assignable to
 * decorated component type [class ChannelEventPublisher]
 * </pre>
 * Na 5.x o {@code EventStore} <b>é</b> o {@code EventSink} — decorar o sink significa decorar o store,
 * e o decorador teria de implementar {@code EventStore} inteiro só para encaminhar eventos. O
 * interceptador de dispatch é aditivo, não substitui componente nenhum, e é exatamente a abstração que
 * o framework oferece para isto.
 *
 * <h2>Por que encaminha em {@code onAfterCommit}</h2>
 * Encaminhar durante o dispatch poria o broker <b>dentro da transação da escrita</b>: broker lento
 * faria o {@code createPost} lento, broker indisponível faria o {@code createPost} <b>falhar</b>. Um
 * post não deve deixar de ser criado porque a mensageria caiu.
 * <p>
 * Em {@code AFTER_COMMIT} o evento já está no event store — que é o log durável — e o encaminhamento
 * acontece sobre um fato consumado. É o mesmo mecanismo, pela mesma razão, do
 * {@code AssignDefaultTagOnPostCreated}.
 * <p>
 * Como o Axon espera o {@code CompletableFuture} do after-commit, falha de publicação ainda falha o
 * processamento — só que depois de o evento estar gravado, então o retry <i>reencaminha</i> em vez de
 * reescrever. Entrega ao menos uma vez com o event store como log, sem tabela de outbox própria.
 */
@ApplicationScoped
public class ChannelEventDispatchInterceptor implements EventDispatchInterceptorsProducer {

    private static final Logger log = LoggerFactory.getLogger(ChannelEventDispatchInterceptor.class);

    private final ChannelEventForwarder forwarder;
    private final boolean publishEvents;

    ChannelEventDispatchInterceptor(ChannelEventForwarder forwarder,
            @ConfigProperty(name = "axonposts.messaging.publish-events") boolean publishEvents) {
        this.forwarder = forwarder;
        this.publishEvents = publishEvents;
    }

    @Override
    public List<MessageDispatchInterceptor<EventMessage>> createDispatchInterceptor() {
        if (!publishEvents) {
            log.info("integração com channels desligada (axonposts.messaging.publish-events=false)");
            return List.of();
        }
        log.info("todo evento despachado será encaminhado ao channel axon-events depois do commit");
        return List.of((event, context, chain) -> {
            if (context != null) {
                context.onAfterCommit(committed -> forwarder.forward(event));
            }
            return chain.proceed(event, context);
        });
    }
}
