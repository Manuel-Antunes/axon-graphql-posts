package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.axonframework.messaging.eventstreaming.Tag;

/**
 * As duas chaves de metadata que a integração acrescenta às mensagens, e o codec das tags.
 *
 * <h2>Por que a ORIGEM viaja na mensagem</h2>
 * Para cortar o laço. Todo evento publicado localmente é encaminhado ao broker, e todo evento que chega
 * do broker é <b>apendado no event store local</b> — o que o publica localmente. Sem marca de origem as
 * duas regras se alimentam: o serviço A publica, o B apenda e republica, o A apenda e republica, para
 * sempre. Não é um risco teórico: é o comportamento inevitável das duas regras juntas.
 * <p>
 * A marca resolve isso por uma pergunta que a mensagem responde sozinha: "eu sou o autor deste evento?"
 * Quem não é autor não encaminha. Com isso, cada evento atravessa o broker <b>uma</b> vez, no sentido de
 * quem o produziu para quem o consome, e a topologia de filas deixa de ser a única defesa — o que
 * importa porque binding é configuração, e configuração muda.
 *
 * <h2>Por que as TAGS viajam na mensagem</h2>
 * Porque do outro lado elas não são recuperáveis. As tags saem dos {@code @EventTag} do record pelo
 * {@code TagResolver} do Axon, e o evento que chega do broker tem payload {@code byte[]} — não há
 * record para anotar nem campos para ler. Um append sem tag, num store em aggregate mode, é um evento
 * sem {@code aggregateIdentifier}: ele entra na tabela e <b>nunca</b> é lido de volta ao reidratar o
 * agregado.
 * <p>
 * Elas são lidas de volta pelo {@link ChannelTagResolver}, que decora o resolver do framework em vez de
 * substituí-lo: evento local continua sendo tagueado pelas anotações.
 *
 * <h2>O formato</h2>
 * {@code chave=valor;chave=valor}, com chave e valor percent-encoded. Não é JSON de propósito: isto
 * acaba gravado na coluna {@code metadata} do event store para sempre, e um formato que se lê a olho nu
 * num {@code select} vale mais, aqui, que um que economiza bytes. O encoding é o que garante que um
 * valor com {@code ;} ou {@code =} — perfeitamente legal numa tag — não parta o registro em dois.
 */
public final class ChannelMetadata {

    /** Quem produziu o evento: o {@code quarkus.application.name} do serviço de origem. */
    public static final String ORIGIN = "axon-channel-origin";

    /** As {@code Tag} do evento, achatadas — ver o codec abaixo. */
    public static final String TAGS = "axon-channel-tags";

    private ChannelMetadata() {
    }

    public static String encodeTags(List<AxonEventEnvelope.EventTag> tags) {
        StringBuilder encoded = new StringBuilder();
        for (AxonEventEnvelope.EventTag tag : tags) {
            if (!encoded.isEmpty()) {
                encoded.append(';');
            }
            encoded.append(escape(tag.key())).append('=').append(escape(tag.value()));
        }
        return encoded.toString();
    }

    /** As tags do envelope como o Axon as entende. Sem passar pelo fio — o envelope já as tem. */
    public static Set<Tag> tagsOf(List<AxonEventEnvelope.EventTag> tags) {
        Set<Tag> resolved = new LinkedHashSet<>();
        tags.forEach(tag -> resolved.add(new Tag(tag.key(), tag.value())));
        return resolved;
    }

    public static Set<Tag> decodeTags(String encoded) {
        Set<Tag> tags = new LinkedHashSet<>();
        if (encoded == null || encoded.isBlank()) {
            return tags;
        }
        for (String pair : encoded.split(";")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                tags.add(new Tag(unescape(pair.substring(0, separator)),
                        unescape(pair.substring(separator + 1))));
            }
        }
        return tags;
    }

    private static String escape(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static String unescape(String encoded) {
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
