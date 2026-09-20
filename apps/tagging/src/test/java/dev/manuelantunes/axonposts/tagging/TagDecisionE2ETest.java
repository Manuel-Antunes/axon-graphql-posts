package dev.manuelantunes.axonposts.tagging;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.manuelantunes.axonposts.domain.post.event.PostPreCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope;
import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope.EventTag;
import dev.manuelantunes.axonposts.infrastructure.messaging.ChannelEventIngestion;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * O TRABALHO INTEIRO deste serviço, de uma ponta à outra — sem broker.
 *
 * <h2>O que ele exercita, e por que não é dublagem</h2>
 * O teste entrega um {@code byte[]} ao {@link ChannelEventIngestion} — o mesmo método que o
 * {@code @Incoming} chama — e lê o envelope que saiu pelo canal de saída. Entre uma coisa e outra
 * acontece tudo o que acontece em produção: o envelope é desserializado, o evento é APENDADO no event
 * store local, o append aciona o processor subscribing, o handler despacha o command no
 * {@code AFTER_COMMIT}, o agregado {@code Post} é reidratado do stream e decide, e o evento resultante
 * volta pelo {@code OutboxRouting}.
 * <p>
 * O que muda em relação à produção é só o TRANSPORTE — `smallrye-in-memory` no lugar do RabbitMQ, por
 * três linhas de {@code %test.} no {@code application.properties}. O caminho com broker de verdade
 * continua coberto por {@code apps/posts-api-e2e}, que é quem consegue montar dois processos.
 *
 * <h2>Por que este teste existe</h2>
 * Porque o {@code CompletePostWithDefaultTagCommandTest} prova a DECISÃO e não a FIAÇÃO. Tudo o que
 * este arquivo cobre falha em silêncio: um namespace esquecido em
 * {@code subscribingprocessor.namespaces} manda o handler para um processor que não existe, um
 * {@code @AxonOutbox} com o namespace errado faz o evento não sair, e nenhuma das duas coisas quebra
 * compilação ou teste de unidade.
 */
@QuarkusTest
class TagDecisionE2ETest {

    private static final String OUT = "post-events-out";

    @Inject
    ChannelEventIngestion ingestion;

    @Inject
    ObjectMapper json;

    @Inject
    @Any
    InMemoryConnector connector;

    /**
     * O envelope como o outro serviço o põe no fio.
     * <p>
     * Montá-lo à mão é o ponto: se o {@code AxonEventEnvelope} mudar de forma, é aqui que aparece — e
     * a marca de origem é a do OUTRO serviço, porque com a deste o inbox descartaria a mensagem como
     * eco, que é justamente a guarda que não se quer exercitar aqui.
     */
    private byte[] preCreated(PostId postId) throws Exception {
        PostPreCreatedEvent event = new PostPreCreatedEvent(
                postId, "Saga coreografada", "conteúdo", UserId.of("author-1"), Instant.now());
        AxonEventEnvelope envelope = new AxonEventEnvelope(
                "posts.PostPreCreated#1.0.0",
                UUID.randomUUID().toString(),
                Instant.now(),
                Map.of("axon-channel-origin", "quarkus-axon-graphql-posts"),
                List.of(new EventTag("postId", postId.value())),
                AxonEventEnvelope.encodePayload(json.writeValueAsBytes(event)));
        return json.writeValueAsBytes(envelope);
    }

    private InMemorySink<Object> published() {
        return connector.sink(OUT);
    }

    @Test
    void aPreCreatedPostComesOutCompleteWithTheDefaultTag() throws Exception {
        PostId postId = PostId.newId();
        int before = published().received().size();

        ingestion.ingest(preCreated(postId));

        await().untilAsserted(() -> assertThat(published().received()).hasSizeGreaterThan(before));

        AxonEventEnvelope out = (AxonEventEnvelope) published().received().getLast().getPayload();

        // O evento que sai é do agregado POST, no namespace `posts` — o fato é do Post, e quem o
        // decidiu foi este serviço. É o que mantém "dois serviços tocam o mesmo ciclo de vida" honesto.
        assertThat(out.messageType()).startsWith("posts.PostCreated");
        assertThat(out.tags()).singleElement()
                .satisfies(tag -> assertThat(tag.value()).isEqualTo(postId.value()));

        // A ORIGEM é deste serviço: é ela que faz o outro lado não tratar a volta como eco.
        assertThat(out.metadata()).containsEntry("axon-channel-origin", "axonposts-tagging");

        String payload = new String(out.decodePayload());
        assertThat(payload).contains(Tag.DEFAULT_NAME).contains(Tag.DEFAULT_ID.value());
    }

    /**
     * REENTREGAR A MESMA MENSAGEM NÃO PRODUZ UMA SEGUNDA DECISÃO.
     * <p>
     * Aqui quem segura é o INBOX — a mesma mensagem, o mesmo identificador, descartada no commit do
     * append. É a guarda do meio das três, e a única que este teste consegue isolar: a marca de origem
     * não se aplica (a origem é do outro serviço) e a do agregado só entraria em cena se o inbox
     * falhasse.
     */
    @Test
    void redeliveringTheSameMessageDecidesNothingTwice() throws Exception {
        PostId postId = PostId.newId();
        byte[] message = preCreated(postId);
        int before = published().received().size();

        ingestion.ingest(message);
        await().untilAsserted(() -> assertThat(published().received()).hasSize(before + 1));

        ingestion.ingest(message);

        // Nada a esperar: o que se afirma é uma AUSÊNCIA. A janela é o tempo de a decisão acontecer
        // se o inbox tivesse deixado passar — e ela já aconteceu uma vez acima, em menos que isto.
        Thread.sleep(1_000);
        assertThat(published().received()).hasSize(before + 1);
    }
}
