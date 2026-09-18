package dev.manuelantunes.axonposts.infrastructure.messaging;

import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A implementação de {@link ChannelAddressing} para RabbitMQ — e o <b>único</b> arquivo deste pacote
 * que importa algo de um broker.
 *
 * <h2>Acrescentar Kafka</h2>
 * Uma classe irmã desta, com {@code connector()} devolvendo {@code smallrye-kafka} e
 * {@code OutgoingKafkaRecordMetadata.builder().withKey(address.orderingKey())}. Ela NÃO substitui esta:
 * as duas convivem, e quem escolhe entre elas é o conector do canal. Nada mais muda — nem o
 * encaminhador, nem o envelope, nem a serialização, nem os outros outboxes.
 *
 * <h2>A routing key tem TRÊS segmentos: {@code namespace.localName.orderingKey}</h2>
 * Porque uma routing key de exchange {@code topic} serve a duas coisas que brigam: <b>seleção</b> (quem
 * se vincula ao quê) e <b>ordenação</b> (o que cai no mesmo consumidor). Com os dois primeiros segmentos
 * vindo do {@code MessageType}, um consumidor se vincula a {@code posts.PostCreated.*} e recebe só o que
 * quer. Com o terceiro vindo da tag do agregado, a chave inteira identifica a instância — e um exchange
 * {@code x-consistent-hash} à frente distribui por ela, preservando a ordem por agregado.
 * <p>
 * <b>Limite honesto:</b> num exchange {@code topic} simples, ordem por agregado só vale com um consumidor
 * por fila. O terceiro segmento é o que torna a alternativa possível, não o que já a garante.
 * <p>
 * É o {@code fullName()} do {@code QualifiedName}, e não o {@code name()} do tipo: o segundo não garante
 * o namespace no valor, e sem ele a binding {@code posts.*} não casa — a mensagem sai, o exchange a
 * descarta, e NADA no log diz que isso aconteceu. Medido: 274 eventos publicados, 0 recebidos. Hoje o
 * {@link EventAddress} já entrega o nome qualificado pronto, e o erro deixou de ser alcançável daqui.
 * <p>
 * Não há {@code switch} sobre tipos de evento, e é o ponto: evento novo no domínio já sai roteado,
 * porque a chave é derivada da anotação que o evento já tem.
 */
@ApplicationScoped
public class RabbitMqAddressing implements ChannelAddressing {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqAddressing.class);

    @Override
    public String connector() {
        return "smallrye-rabbitmq";
    }

    @Override
    public Metadata addressing(EventAddress address) {
        String routingKey = address.qualifiedName() + "." + address.orderingKey();
        log.debug("routing key = {}  (tags: {})", routingKey, address.tags());
        return Metadata.of(OutgoingRabbitMQMetadata.builder()
                .withRoutingKey(routingKey)
                .withHeader("axon-message-type", address.messageType())
                .withHeader("axon-message-id", address.identifier())
                .build());
    }
}
