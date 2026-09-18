package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.List;

import org.axonframework.messaging.eventhandling.EventMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A implementação de {@link ChannelAddressing} para RabbitMQ — e o <b>único</b> arquivo deste pacote
 * que importa algo de um broker.
 *
 * <h2>Trocar para Kafka</h2>
 * Substituir esta classe por uma que devolva {@code OutgoingKafkaRecordMetadata.builder().withKey(...)}
 * e trocar o {@code connector} do channel em {@code application.properties}. Nada mais muda: nem o
 * publisher, nem a fonte, nem o envelope, nem a serialização.
 *
 * <h2>A routing key</h2>
 * Vem do {@code MessageType} do evento, que é o {@code namespace} e o {@code name} do {@code @Event}.
 * Um {@code PostCreatedEvent} anotado {@code @Event(namespace = "posts", name = "PostCreated")} sai
 * como {@code posts.PostCreated}, e num exchange {@code topic} isso deixa cada consumidor escolher no
 * vínculo: {@code posts.PostCreated} para quem só quer criação, {@code posts.*} para quem quer tudo.
 * <p>
 * É {@code qualifiedName().fullName()}, e não {@code type().name()}: o segundo não garante o namespace
 * no valor, e sem ele a binding {@code posts.*} não casa — a mensagem sai, o exchange a descarta, e
 * NADA no log diz que isso aconteceu. Medido: 274 eventos publicados, 0 recebidos.
 * <p>
 * Não há {@code switch} sobre tipos de evento aqui, e é o ponto: evento novo no domínio já sai
 * roteado, porque a chave é derivada da anotação que o evento já tem.
 */
@ApplicationScoped
public class RabbitMqAddressing implements ChannelAddressing {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqAddressing.class);

    private final List<String> orderingTagKeys;

    RabbitMqAddressing(
            @ConfigProperty(name = "axonposts.messaging.ordering-tag-keys") List<String> orderingTagKeys) {
        this.orderingTagKeys = orderingTagKeys;
    }

    /**
     * A routing key tem <b>três</b> segmentos: {@code namespace.localName.tagValue}.
     *
     * <h3>Por que três, e não dois</h3>
     * Porque uma routing key de exchange {@code topic} serve a duas coisas que brigam: <b>seleção</b>
     * (quem se vincula ao quê) e <b>ordenação</b> (o que cai no mesmo consumidor). Com os dois
     * primeiros segmentos vindo do {@code MessageType}, um consumidor se vincula a
     * {@code posts.PostCreated.*} e recebe só o que quer. Com o terceiro vindo da <b>tag do agregado</b>,
     * a chave inteira identifica a instância — e um exchange {@code x-consistent-hash} à frente
     * distribui por ela, preservando a ordem por agregado.
     * <p>
     * <b>Limite honesto:</b> num exchange {@code topic} simples, ordem por agregado só vale com um
     * consumidor por fila. O terceiro segmento é o que torna a alternativa possível, não o que já a
     * garante.
     *
     * <h3>Qual tag vira a chave</h3>
     * A primeira que casar com {@code axonposts.messaging.ordering-tag-keys}. Um {@code PostCreatedEvent}
     * tem {@code @EventTag postId} e {@code @EventTag authorId}: a preferência é o que decide que a
     * ordem é por post, e não por autor. Sem isso a escolha seria alfabética — {@code authorId} — e a
     * ordenação seria pelo agregado errado, silenciosamente.
     */
    @Override
    public Metadata addressing(EventMessage event, List<AxonEventEnvelope.EventTag> tags) {
        String type = event.type().qualifiedName().fullName();
        String orderingValue = orderingTagKeys.stream()
                .flatMap(preferred -> tags.stream().filter(tag -> tag.key().equals(preferred)))
                .map(AxonEventEnvelope.EventTag::value)
                .findFirst()
                .orElseGet(() -> tags.isEmpty() ? "none" : tags.get(0).value());

        log.debug("routing key = {}.{}  (tags: {})", type, orderingValue, tags);
        return Metadata.of(OutgoingRabbitMQMetadata.builder()
                .withRoutingKey(type + "." + orderingValue)
                .withHeader("axon-message-type", event.type().toString())
                .withHeader("axon-message-id", event.identifier())
                .build());
    }
}
