package dev.manuelantunes.axonposts.interfaces.messaging;

import java.io.IOException;

import dev.manuelantunes.axonposts.infrastructure.messaging.ChannelEventIngestion;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A <b>volta da saga</b>: o post voltando completo do serviço que decidiu a primeira tag.
 *
 * <h2>Um listener por propósito</h2>
 * Esta aplicação ingere uma fatia só do fluxo — o {@code posts.PostCreated} que ela própria não
 * produziu. Tudo o mais que trafega no exchange é dela (e a marca de origem descartaria de qualquer
 * forma), então há um canal e não mais.
 * <p>
 * Acrescentar outro é este arquivo copiado com outra routing key, mais um bloco em
 * {@code application.properties}. É de propósito que seja explícito: <b>quais fatias esta máquina
 * ingere</b> é decisão da aplicação, e ela fica escrita aqui em vez de diluída numa lista de bindings.
 *
 * <h2>As duas anotações, e as duas são medidas</h2>
 * {@code @Blocking} porque a ingestão faz JPA dentro de uma transação JTA, e sem ele o SmallRye invoca
 * este método no event-loop: {@code @Transactional cannot start a JTA transaction within a reactive
 * pipeline}, a mensagem nacked, e do lado de fora só esperas estourando.
 * <p>
 * {@code ordered = false} — e este é o {@code @Blocking} do <b>reactive messaging</b>, não o de
 * {@code smallrye-common}, porque só ele tem o atributo — porque com a ordenação ligada a entrega roda
 * numa {@code TaskQueue} do Vert.x, serializada por contexto. A ingestão espera o processamento, que
 * termina publicando no broker; a primeira publicação do processo precisa estabelecer a conexão de
 * saída, e essa conexão seria enfileirada na MESMA TaskQueue que esta thread ocupa. A thread espera a
 * conexão, a conexão espera a thread. O log fica bonito de ruim: a saga inteira aparece funcionando e
 * depois há 60 segundos de silêncio até o Narayana matar a transação.
 * <p>
 * E o retorno é {@code void}: devolvendo {@code CompletionStage} o SmallRye espera o estágio para dar
 * ack e não puxa a próxima mensagem — uma entrega e a fila parada. Com {@code void} o ack sai no retorno
 * do método, ou seja depois do commit, e exceção aqui vira nack.
 */
@ApplicationScoped
public class PostCompletionListener {

    static final String CHANNEL = "post-completed-in";

    private final ChannelEventIngestion ingestion;

    PostCompletionListener(ChannelEventIngestion ingestion) {
        this.ingestion = ingestion;
    }

    @Blocking(ordered = false)
    @Incoming(CHANNEL)
    public void receive(byte[] body) throws IOException {
        ingestion.ingest(body);
    }
}
