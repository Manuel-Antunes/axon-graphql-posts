package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.Set;

import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.Tag;

/**
 * Decora o {@code TagResolver} do Axon para que um evento <b>vindo do broker</b> seja apendado com as
 * tags que ele já tinha na origem.
 *
 * <h2>O problema exato</h2>
 * O resolver do framework lê {@code @EventTag} dos campos do payload. O evento que chega do broker tem
 * payload {@code byte[]} — a conversão para o record só acontece na invocação do handler, depois. Nesse
 * ponto o resolver devolve conjunto <b>vazio</b>, e um evento sem tag num store em aggregate mode é um
 * evento sem {@code aggregateIdentifier}: ele é gravado e nunca mais é lido ao reidratar o agregado.
 * Falha em silêncio — o append retorna com sucesso.
 *
 * <h2>Por que decorar, e não substituir</h2>
 * Porque as duas fontes de tag precisam coexistir: evento local vem de anotação, evento ingerido vem da
 * metadata. Substituir o resolver (o que {@code registerTagResolver} faria) quebraria o caminho local.
 * O delegate é consultado sempre que não há tags na metadata, ou seja, em todo evento produzido aqui.
 */
public class ChannelTagResolver implements TagResolver {

    private final TagResolver delegate;

    public ChannelTagResolver(TagResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public Set<Tag> resolve(EventMessage event) {
        String encoded = event.metadata().get(ChannelMetadata.TAGS);
        if (encoded == null) {
            return delegate.resolve(event);
        }
        Set<Tag> carried = ChannelMetadata.decodeTags(encoded);
        return carried.isEmpty() ? delegate.resolve(event) : carried;
    }
}
