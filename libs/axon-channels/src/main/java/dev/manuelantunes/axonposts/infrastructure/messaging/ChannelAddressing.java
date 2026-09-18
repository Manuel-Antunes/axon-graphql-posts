package dev.manuelantunes.axonposts.infrastructure.messaging;

import org.eclipse.microprofile.reactive.messaging.Metadata;

/**
 * O único ponto deste pacote que sabe qual broker está atrás de um canal.
 *
 * <h2>Por que existe</h2>
 * Endereçamento é, por definição, específico do broker: RabbitMQ roteia por <i>routing key</i> num
 * exchange, Kafka particiona por <i>record key</i>, e as duas coisas se expressam por metadados de
 * saída com APIs distintas ({@code OutgoingRabbitMQMetadata}, {@code OutgoingKafkaRecordMetadata}).
 * Isolar isso aqui é o que faz acrescentar um protocolo ser acrescentar <b>uma</b> implementação. O
 * resto do pacote trabalha com {@code Metadata} genérico do SmallRye.
 *
 * <h2>Por que {@link #connector()}, e o que ele mudou</h2>
 * Antes havia um {@code ChannelAddressing} por PROCESSO: um bean único, injetado no encaminhador. Com
 * isso o protocolo era uma propriedade da aplicação inteira, e "uma parte em Kafka, a outra em
 * RabbitMQ" não era exprimível — o segundo bean derrubaria a partida por ambiguidade.
 * <p>
 * Declarando o conector que serve, o endereçamento passa a ser escolhido <b>por canal</b>:
 * {@link OutboxRouting} lê {@code mp.messaging.outgoing.&lt;canal&gt;.connector} e casa com este valor. Dois
 * outboxes em protocolos diferentes no mesmo serviço deixam de ser um caso especial e viram o caso
 * normal — que é o que "coreografia" exige, já que quem escolhe o transporte é cada aresta, não o nó.
 *
 * <h2>Por que recebe {@link EventAddress}, e não o {@code EventMessage}</h2>
 * Porque ler o evento é uma decisão só, e ela já foi tomada. Uma implementação que recebesse o
 * {@code EventMessage} teria de reextrair o {@code MessageType} e reescolher a tag de ordenação — e
 * duas implementações que refazem a mesma leitura são duas implementações que podem discordar. Aqui
 * cada uma faz exclusivamente o que só ela sabe fazer: traduzir para o metadado do broker dela.
 */
public interface ChannelAddressing {

    /**
     * O valor de {@code mp.messaging.outgoing.&lt;canal&gt;.connector} que esta implementação atende —
     * {@code smallrye-rabbitmq}, {@code smallrye-kafka}, {@code smallrye-pulsar}…
     */
    String connector();

    Metadata addressing(EventAddress address);
}
