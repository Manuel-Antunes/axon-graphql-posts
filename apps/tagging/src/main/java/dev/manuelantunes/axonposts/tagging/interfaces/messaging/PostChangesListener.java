package dev.manuelantunes.axonposts.tagging.interfaces.messaging;

import java.io.IOException;

import dev.manuelantunes.axonposts.infrastructure.messaging.ChannelEventIngestion;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * O canal em que este serviço <b>não age</b>: ele só mantém o stream do Post completo no store dele.
 *
 * <h2>Por que replicar eventos aos quais ninguém reage</h2>
 * Porque este serviço <b>escreve</b> no stream do Post — é ele que apenda o {@code PostCreated} que
 * completa o post. E num event store em <i>aggregate mode</i> a posição de um append vem de ter lido o
 * stream antes: se faltarem eventos aqui, a leitura devolve menos do que existe e o append seguinte
 * entra numa sequência já ocupada. O banco recusa, com
 * {@code duplicate key value violates unique constraint "uk_aggregateevententry_aggregate"}.
 * <p>
 * Ou seja: dois serviços que escrevem no mesmo agregado precisam ver a <b>mesma história</b>, mesmo que
 * um deles não reaja a metade dela. Este canal é o que paga esse preço — e o paga barato, porque não há
 * handler nenhum: a mensagem entra, vira linha no store, e acabou.
 *
 * <h2>Três routing keys, uma fila</h2>
 * {@code posts.PostUpdated.*}, {@code posts.PostDeleted.*} e {@code posts.PostRestored.*} — tudo que
 * muda um Post depois de criado e que este serviço não produz. O {@code PostPreCreated} vem pelo canal
 * vizinho porque ele <b>aciona trabalho</b>, e o {@code PostCreated} não vem por nenhum: é este serviço
 * que o publica, e a marca de origem o descartaria.
 * <p>
 * Evento novo no ciclo de vida do Post = mais uma routing key na configuração deste canal. Esquecer não
 * quebra nada na hora — quebra no próximo append deste serviço àquele agregado, que é o tipo de defeito
 * que aparece longe da causa. Por isso está escrito aqui.
 */
@ApplicationScoped
public class PostChangesListener {

    static final String CHANNEL = "post-changes-in";

    private final ChannelEventIngestion ingestion;

    PostChangesListener(ChannelEventIngestion ingestion) {
        this.ingestion = ingestion;
    }

    @Blocking(ordered = false)
    @Incoming(CHANNEL)
    public void receive(byte[] body) throws IOException {
        ingestion.ingest(body);
    }
}
