package dev.manuelantunes.axonposts.infrastructure.messaging;

import org.axonframework.messaging.eventhandling.EventMessage;
import org.eclipse.microprofile.reactive.messaging.Metadata;

/**
 * O único ponto deste pacote que sabe qual broker está atrás do channel.
 *
 * <h2>Por que existe</h2>
 * Endereçamento é, por definição, específico do broker: RabbitMQ roteia por <i>routing key</i> num
 * exchange, Kafka particiona por <i>record key</i>, e as duas coisas se expressam por metadados de
 * saída com APIs distintas ({@code OutgoingRabbitMQMetadata}, {@code OutgoingKafkaRecordMetadata}).
 * <p>
 * Isolar isso aqui é o que faz trocar de broker ser trocar <b>uma</b> implementação, sem mexer no
 * publisher. O resto do pacote trabalha com {@code Metadata} genérico do SmallRye.
 *
 * <h2>Por que recebe o evento inteiro</h2>
 * Porque quem decide a chave é quem conhece o domínio <i>e</i> o broker. Do {@code EventMessage} sai o
 * {@code MessageType} (o {@code namespace}/{@code name} do {@code @Event}) para o destino, e da
 * metadata ou do payload sai a identidade do agregado para a chave de ordenação.
 * <p>
 * Isso não é enfeite: eventos do mesmo agregado com a mesma chave caem na mesma partição ou no mesmo
 * consumidor, e é o que preserva ordem <b>por agregado</b> — a razão pela qual routing key importa.
 */
public interface ChannelAddressing {

    Metadata addressing(EventMessage event, java.util.List<AxonEventEnvelope.EventTag> tags);

    /** Conector sem endereçamento por mensagem — o in-memory dos testes, por exemplo. */
    ChannelAddressing NONE = (event, tags) -> Metadata.empty();
}
