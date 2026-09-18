package dev.manuelantunes.axonposts.infrastructure.messaging;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * O formato de fio. Um envelope de campos simples: o que o Axon precisa para reconstruir um
 * {@code EventMessage} do outro lado, e nada além.
 *
 * @param messageType o {@code MessageType} serializado por {@code toString()} — carrega o
 *                    {@code namespace}, o {@code name} e a {@code version} do {@code @Event}. É o que
 *                    permite ao consumidor resolver o tipo sem compartilhar classes.
 * @param identifier  o id do evento. É o que dá idempotência a quem consome.
 * @param timestamp   ISO-8601, para o evento reconstruído manter o instante original em vez de ganhar
 *                    o horário da entrega.
 * @param metadata    a metadata do Axon, que é {@code Map<String, String>} — correlação e causalidade
 *                    atravessam por aqui.
 * @param tags        as {@code Tag} do evento, resolvidas pelo {@code TagResolver} do Axon — ou seja,
 *                    os {@code @EventTag} do record ({@code postId}, {@code authorId}). São as
 *                    <b>propriedades do evento</b> no sentido do framework, e por isso são elas que dão
 *                    a chave de roteamento e a identidade de fronteira de consistência (DCB). Sem elas
 *                    no fio, um consumidor não consegue reconstruir nem uma nem outra.
 * @param payload     o corpo convertido pelo {@code EventConverter} do framework, em <b>base64 com
 *                    padding</b>. É uma {@code String} e não um {@code byte[]} de propósito — ver
 *                    abaixo.
 */
/*
 * POR QUE O PAYLOAD É String E O BASE64 É FEITO À MÃO
 * ===================================================
 * Ele era `byte[]`, deixando a codificação para quem serializasse o envelope. Isso corrompia parte das
 * mensagens, e de um jeito quase impossível de suspeitar: o conector de saída escreve `byte[]` em
 * base64 SEM padding, e o Jackson da entrada desserializa com o variant `MIME-NO-LINEFEEDS`, que EXIGE
 * padding. O resultado:
 *   InvalidFormatException: Cannot deserialize value of type `byte[]` from String "":
 *   Unexpected end of base64-encoded String: base64 variant 'MIME-NO-LINEFEEDS' expects padding
 *
 * E o pior detalhe: base64 só precisa de padding quando o tamanho do conteúdo não é múltiplo de 3. Ou
 * seja, a falha depende do TAMANHO DO PAYLOAD — dois terços das mensagens quebram e um terço passa. Um
 * post com título de um tamanho funciona, com outro não. Medido: a saga fechando numa execução e não
 * fechando na seguinte, com a mesma versão do código.
 *
 * Com `String` + `Base64.getEncoder()` (que sempre coloca padding) e `getMimeDecoder()` (que tolera a
 * ausência dele), a codificação deixa de depender de quem serializa o envelope — que é o requisito
 * óbvio de um formato de fio, e era justamente o que estava terceirizado.
 */
/*
 * O @RegisterForReflection abaixo é obrigatório em GraalVM native, e falha de um jeito enganoso sem
 * ele: o Jackson não encontra os acessores do record e o envio morre com
 *   `EncodeException: No serializer found for class AxonEventEnvelope
 *    and no properties discovered to create BeanSerializer`
 * A consequência medida no binário nativo: as QUERIES funcionam normalmente, e só a ESCRITA falha —
 * com `code: "encode"` no GraphQL —, porque é ela que publica no channel. Quem olhar só as leituras
 * conclui que o native está são.
 *
 * Quarkus registra automaticamente os tipos que ele mesmo vê em REST e mensageria da APLICAÇÃO; um
 * record que vive numa LIB externa não entra nessa conta.
 */
@RegisterForReflection
public record AxonEventEnvelope(
        String messageType,
        String identifier,
        Instant timestamp,
        Map<String, String> metadata,
        List<EventTag> tags,
        String payload) {

    /** Uma {@code Tag} do Axon achatada para o fio: {@code Tag(key, value)}. */
    public record EventTag(String key, String value) {
    }

    /** Com padding, sempre — é o que o decodificador do outro lado pode exigir. */
    public static String encodePayload(byte[] payload) {
        return Base64.getEncoder().encodeToString(payload);
    }

    /**
     * Decodificador tolerante de propósito: ele aceita base64 sem padding e com quebras de linha. O
     * envelope que ESTE código produz é sempre bem formado; a tolerância é para o dia em que o produtor
     * for outra implementação, que é exatamente o caso de um formato de fio.
     */
    public byte[] decodePayload() {
        return Base64.getMimeDecoder().decode(payload);
    }
}
