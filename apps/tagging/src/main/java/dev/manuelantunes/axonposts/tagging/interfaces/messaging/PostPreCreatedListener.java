package dev.manuelantunes.axonposts.tagging.interfaces.messaging;

import java.io.IOException;

import dev.manuelantunes.axonposts.infrastructure.messaging.ChannelEventIngestion;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * O canal em que este serviço <b>age</b>: um post nasceu sem tag.
 *
 * <h2>Por que ele é separado do canal de réplica</h2>
 * Porque as duas fatias têm exigências diferentes. Esta é o trabalho do serviço: cada mensagem vira uma
 * decisão e um evento publicado, e uma falha aqui precisa ser vista. A outra
 * ({@link PostChangesListener}) só mantém o stream local completo, e nada reage a ela.
 * <p>
 * Filas separadas dão o que uma fila só não dá: uma mensagem-veneno de atualização não é rejeitada junto
 * com as decisões pendentes, dá para escalar o consumo de uma sem mexer na outra, e
 * {@code list_bindings} no broker passa a descrever o sistema em vez de dizer "esta app escuta tudo".
 *
 * <h2>As anotações</h2>
 * {@code @Blocking(ordered = false)} e retorno {@code void}, pelas razões medidas em
 * {@code PostCompletionListener} do outro serviço — JPA fora do event-loop, e a TaskQueue do Vert.x
 * causando deadlock com a primeira publicação de saída.
 */
@ApplicationScoped
public class PostPreCreatedListener {

    static final String CHANNEL = "post-precreated-in";

    private final ChannelEventIngestion ingestion;

    PostPreCreatedListener(ChannelEventIngestion ingestion) {
        this.ingestion = ingestion;
    }

    @Blocking(ordered = false)
    @Incoming(CHANNEL)
    public void receive(byte[] body) throws IOException {
        ingestion.ingest(body);
    }
}
