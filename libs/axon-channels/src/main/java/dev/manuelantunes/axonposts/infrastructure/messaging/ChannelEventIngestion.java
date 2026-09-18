package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * A ENTRADA da integração: a mensagem que chega do broker é <b>apendada no event store local</b>.
 *
 * <h2>Isto é o MECANISMO. Quem declara os canais é a aplicação</h2>
 * Esta classe não tem {@code @Incoming} e não conhece nome de fila nenhum: ela recebe bytes e os
 * transforma em evento no store. Cada serviço escreve os próprios listeners — um por canal, cada um
 * vinculado à sua routing key — em {@code infrastructure/messaging}.
 * <p>
 * Houve uma versão com um {@code @Incoming("axon-inbox")} aqui dentro: um canal só por serviço, com o
 * argumento de que "nome de fila é configuração, não código". O argumento estava certo e a conclusão
 * errada — <b>quais fatias do fluxo esta máquina ingere</b> é decisão da aplicação, não da plataforma.
 * Um canal único obriga todo evento que interessa ao serviço a entrar pela mesma fila, e com isso perdem-se
 * três coisas concretas:
 * <ul>
 *   <li><b>isolamento de falha</b>: uma mensagem-veneno de um tipo é rejeitada junto com o fluxo dos
 *       outros, porque a fila é a mesma;</li>
 *   <li><b>vazão por fatia</b>: não dá para dar mais consumidores ao fluxo quente e menos ao frio, nem
 *       para pausar um sem pausar o resto;</li>
 *   <li><b>legibilidade da topologia</b>: com um canal por propósito, {@code list_bindings} no broker
 *       DESCREVE o sistema. Com um canal só, ele diz "esta app escuta tudo".</li>
 * </ul>
 * Acrescentar um canal é uma classe de poucas linhas e um bloco de configuração — e é assim de propósito:
 * a decisão fica visível no código do serviço que a toma.
 *
 * <h2>Por que isto substituiu o {@code ChannelBackedEventSource}</h2>
 * A versão anterior ligava a fila diretamente a um {@code SubscribingEventProcessor}: o processor lia
 * da fila. Funcionava e tinha três furos estruturais, não de implementação:
 * <ol>
 *   <li><b>sem token, sem replay, sem segmento.</b> Token é "recomece do evento N", e fila não sabe
 *       responder isso — ack dá entrega ao menos uma vez, não posição. Logo nada de reprocessar uma
 *       projeção do zero, e nada de dividir a carga entre nós;</li>
 *   <li><b>o evento não era durável deste lado.</b> Ele existia no store de quem o produziu e na
 *       memória de quem o consumia. Restart no meio de uma saga perdia o passo intermediário — e uma
 *       saga coreografada é feita de passos intermediários;</li>
 *   <li><b>a entrega travava.</b> Medido: exatamente uma mensagem processada com sucesso e a fila
 *       inerte em seguida, sem erro e sem nack, porque a espera pelo processamento e a thread da
 *       entrega disputavam o mesmo pool.</li>
 * </ol>
 * Apendando no store, o broker volta a ser só <b>transporte</b>: quem alimenta os processors é o store,
 * como em qualquer aplicação Axon sem mensageria. Os três furos deixam de existir de uma vez — não
 * porque foram consertados, mas porque a fila saiu do caminho entre o evento e o processor.
 *
 * <h2>O que faz de cada entrega uma coisa só</h2>
 * Três guardas, em ordem, e cada um cobre o que o outro não cobre:
 * <ol>
 *   <li><b>origem:</b> evento que este serviço produziu e voltou pelo broker é descartado. Corta o
 *       laço de reenvio — ver {@link ChannelMetadata};</li>
 *   <li><b>inbox:</b> {@link MessageInbox} grava o identificador no MESMO commit do append. Reentrega
 *       encontra a linha e não apenda de novo;</li>
 *   <li><b>o agregado:</b> o handler do outro lado decide contra o próprio estado. É a última linha de
 *       defesa e a única que sobrevive a um inbox limpo.</li>
 * </ol>
 *
 * <h2>Fila por SERVIÇO e por PROPÓSITO</h2>
 * As duas coisas, e por razões diferentes. Por serviço porque no RabbitMQ a cópia é por fila vinculada,
 * não por consumidor: duas aplicações na mesma fila competem pelas mensagens em vez de receberem cópias,
 * e a que descarta dá ack e mata a mensagem da outra — medido nesta base, 274 eventos publicados e 1
 * entregue. Por propósito porque é o que dá isolamento de falha, vazão por fatia e uma topologia legível.
 */
@ApplicationScoped
public class ChannelEventIngestion {

    private static final Logger log = LoggerFactory.getLogger(ChannelEventIngestion.class);

    private final Configuration axon;
    private final MessageInbox inbox;
    private final ObjectMapper json;
    private final String applicationName;

    /**
     * {@code @Inject} explícito, contra a convenção do projeto: com o construtor sem argumentos que o
     * CDI exige para o client proxy passam a existir dois, e a regra "o ArC usa o único construtor com
     * parâmetros" só vale quando há um. Sem a anotação o ArC escolhe o sem parâmetros e todo campo fica
     * nulo — o sintoma é {@code NullPointerException} na entrega, com nack e nada do lado de fora
     * indicando que a integração está morta.
     */
    @Inject
    ChannelEventIngestion(Configuration axon, MessageInbox inbox, ObjectMapper json,
            @ConfigProperty(name = "quarkus.application.name") String applicationName) {
        this.axon = axon;
        this.inbox = inbox;
        this.json = json;
        this.applicationName = applicationName;
    }

    /** Exigido pelo CDI para o client proxy. */
    ChannelEventIngestion() {
        this(null, null, null, null);
    }

    /**
     * Ingere o corpo de uma mensagem. É o que os listeners de cada aplicação chamam.
     *
     * <h3>A transação é a garantia — e ela é a da UNIDADE DE TRABALHO, não um {@code @Transactional}</h3>
     * A linha do inbox e o append acontecem <b>dentro do mesmo</b>
     * {@code unitOfWorkFactory().create("axon-inbox")}. O {@code quarkus-axon-transaction} faz
     * begin-or-join no JTA: como não há transação aberta quando este método é chamado, ele <b>abre</b> —
     * e é a unidade de trabalho que commita. Os dois vão juntos ou nenhum vai, que é o que impede o
     * estado intermediário fatal: evento apendado sem registro de recebimento, que uma reentrega
     * duplicaria.
     *
     * <h3>NÃO PONHA {@code @Transactional} AQUI (nem no listener). O que ele quebra é invisível daqui</h3>
     * Houve um, e por muito tempo. Com ele existe uma transação JTA <b>antes</b> da unidade de trabalho,
     * então ela <b>junta</b> em vez de abrir — e o commit dela deixa de ser o commit da transação.
     * <p>
     * A atomicidade continua valendo, então nada nesta classe muda de comportamento. O que muda é a
     * subscription do OUTRO serviço: o {@code SimpleQueryBus} adia os updates para o after-commit do
     * {@code ProcessingContext}, e com a unidade de trabalho apenas juntando, esse after-commit dispara
     * com a transação JTA ainda aberta. A entrega executa o assinante, que lê o banco noutra thread,
     * dentro da transação que está commitando:
     * <pre>
     * ARJUNA012125: TwoPhaseCoordinator.beforeCompletion - failed ... ConcurrentModificationException
     * ARJUNA012108: CheckedAction::check - atomic action ... aborting with 2 threads active!
     * This statement has been closed.
     * </pre>
     * Medido nos dois sentidos com {@code docker/e2e/run.sh}: <b>11 de 12</b> com o {@code @Transactional},
     * <b>12 de 12</b> sem ele. Nenhum teste do Surefire pega isso — em teste o tagueamento é dublado em
     * processo e esta classe não roda. Quem trava é
     * {@code AxonWiringTest.theIngestionOwnsItsOwnTransaction}, que confere a ausência da anotação.
     *
     * <h3>O que o LISTENER precisa garantir</h3>
     * Duas coisas, e as duas estão documentadas em cada um deles: {@code @Blocking(ordered = false)} —
     * porque aqui dentro há JPA e JTA, que não rodam no event-loop, e porque a ordenação do Vert.x
     * causa deadlock com a primeira publicação de saída — e retorno {@code void}, para o ack sair depois
     * do commit.
     *
     * <h3>O que o LISTENER precisa garantir</h3>
     * Duas coisas, e as duas estão documentadas em cada um deles: {@code @Blocking(ordered = false)} —
     * porque aqui dentro há JPA e JTA, que não rodam no event-loop, e porque a ordenação do Vert.x
     * causa deadlock com a primeira publicação de saída — e retorno {@code void}, para o ack sair depois
     * do commit.
     */
    public void ingest(byte[] body) throws IOException {

        try {
            ingestEnvelope(body);
        } catch (RuntimeException | IOException failure) {
            /*
             * O log É a mudança, e ele existe por uma razão medida: com `failure-strategy=reject` o
             * SmallRye descarta a mensagem e NÃO registra a causa — sai só
             *   SRMSG17013: A message sent to channel `axon-inbox` has been nacked, ignoring the
             *   failure and marking the RabbitMQ message as rejected
             * numa thread do event-loop, sem stack, sem tipo de exceção, sem nada. Do lado de fora a
             * integração simplesmente não acontece, e o único sinal é a saga não fechar.
             *
             * Rethrow depois de logar: a mensagem continua sendo rejeitada (é o comportamento certo
             * para mensagem-veneno), mas agora dá para saber por quê.
             */
            log.error("inbox ← falha ao ingerir a mensagem; ela será REJEITADA e perdida. Corpo ({} bytes): {}",
                    body.length, new String(body, java.nio.charset.StandardCharsets.UTF_8), failure);
            throw failure;
        }
    }

    private void ingestEnvelope(byte[] body) throws IOException {
        AxonEventEnvelope envelope = json.readValue(body, AxonEventEnvelope.class);
        String origin = envelope.metadata().get(ChannelMetadata.ORIGIN);

        if (applicationName.equals(origin)) {
            log.debug("inbox ← {} ({}) descartado: eco do próprio serviço",
                    envelope.messageType(), envelope.identifier());
            return;
        }

        EventMessage event = reconstitute(envelope);
        Set<Tag> tags = ChannelMetadata.tagsOf(envelope.tags());

        unitOfWorkFactory().create("axon-inbox").executeWithResult(context -> {
            if (!inbox.register(envelope.identifier(), envelope.messageType(), origin)) {
                log.info("inbox ← {} ({}) descartado: já ingerido antes",
                        envelope.messageType(), envelope.identifier());
                return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
            }
            log.debug("inbox ← {} ({}) de '{}' → apendando no event store local",
                    envelope.messageType(), envelope.identifier(), origin);
            return append(context, event, tags);
        }).join();
    }

    /**
     * O payload continua {@code byte[]}, exatamente como saiu do event store de quem publicou: quem o
     * converte para o record é o Axon, na invocação do handler, com o {@code EventConverter} e o
     * {@code MessageType} do envelope. Não há um segundo caminho de desserialização aqui — e é isso que
     * permite ingerir um evento cuja classe este serviço <b>não tem</b>.
     * <p>
     * As tags entram na metadata porque o {@code TagResolver} do framework não as encontraria num
     * {@code byte[]}; ver {@link ChannelTagResolver}.
     */
    private EventMessage reconstitute(AxonEventEnvelope envelope) {
        Map<String, String> metadata = new LinkedHashMap<>(envelope.metadata());
        metadata.put(ChannelMetadata.TAGS, ChannelMetadata.encodeTags(envelope.tags()));
        return new GenericEventMessage(
                envelope.identifier(),
                MessageType.fromString(envelope.messageType()),
                envelope.decodePayload(),
                metadata,
                envelope.timestamp());
    }

    /**
     * Apenda o evento recebido no stream do agregado dele, <b>lendo o stream antes</b>.
     *
     * <h3>Por que ler antes é obrigatório, e não otimização</h3>
     * Em <i>aggregate mode</i> — o único modo do event store JPA do Axon 5.3.1 — a posição do evento no
     * stream NÃO é inferida na escrita: ela vem do {@code ConsistencyMarker} que o ato de SOURCING deixa
     * no {@code ProcessingContext}. Apendar sem ter lido grava na sequência 0, e se o agregado já tem um
     * evento ali o banco recusa:
     * <pre>
     * duplicate key value violates unique constraint "uk_aggregateevententry_aggregate"
     * </pre>
     * O detalhe que engana: no serviço que vê o agregado pela PRIMEIRA vez isso funciona, porque a
     * sequência 0 está livre. A falha aparece só onde o agregado já tem história — que é exatamente a
     * volta de uma saga. Metade da coreografia funcionava.
     * <p>
     * O {@code reduce} existe para CONSUMIR o stream: um {@code MessageStream} não lido não fixa marker
     * nenhum. E ler antes de escrever é o que dá concorrência otimista ao append — é o mesmo que
     * qualquer command handler faz ao carregar o agregado.
     *
     * <h3>A unidade de trabalho vem da FÁBRICA, e isso não é detalhe</h3>
     * {@code unitOfWorkFactory().create(...)}, e não {@code new UnitOfWork()}. Construída à mão ela não
     * tem o {@code ApplicationContext} da configuração — o agendador de trabalho nunca roda, o future do
     * sourcing nunca completa, e o sintoma é a ingestão <b>pendurada</b> até o Narayana matar a transação
     * em 60 segundos. Nenhum erro, nenhuma stack: só silêncio e uma reentrega um minuto depois.
     * <p>
     * A fábrica registrada pela extensão é transacional: ela faz begin-or-join no JTA. Como não há
     * transação aberta quando a ingestão começa, ela <b>abre</b> — e a mesma unidade de trabalho carrega
     * a linha do inbox e o append, no mesmo commit. Com duas transações, um crash entre elas deixaria o
     * evento apendado sem registro de recebimento, e a reentrega duplicaria.
     * <p>
     * Que ela ABRA, e não junte, é o que mantém o after-commit do Axon depois do commit de verdade —
     * ver {@link #ingest}.
     */
    private java.util.concurrent.CompletableFuture<Void> append(
            org.axonframework.messaging.core.unitofwork.ProcessingContext context,
            EventMessage event, Set<Tag> tags) {
        if (tags.isEmpty()) {
            /*
             * Sem tag não há agregado a que pertencer, logo não há sequência a respeitar. Isto não
             * deveria acontecer — um evento sem tag num store em aggregate mode nunca é lido de volta —,
             * e fica como caminho explícito em vez de comportamento indefinido.
             */
            log.warn("inbox ← {} sem tags: apendando sem fronteira de agregado", event.type());
            return eventStore().publish(context, event);
        }
        EventStoreTransaction transaction = eventStore().transaction(context);
        return transaction
                .source(SourcingCondition.conditionFor(EventCriteria.havingTags(tags)))
                .reduce(0L, (count, entry) -> count + 1L)
                .thenAccept(sourced -> {
                    log.debug("inbox ← o stream de {} tinha {} evento(s); apendando em seguida",
                            tags, sourced);
                    transaction.appendEvent(event);
                });
    }

    /**
     * Resolvidos na primeira ingestão, e não no construtor, pela mesma razão do
     * {@link ChannelEventForwarder}: tocar a configuração do Axon durante a construção do bean cai na
     * ordem de partida da extensão. Na 5.x o {@code EventStore} <b>é</b> o {@code EventSink} — foi o que
     * derrubou a tentativa de decorar o sink, e é o que faz este append ir para o store.
     */
    private volatile EventStore store;
    private volatile UnitOfWorkFactory unitsOfWork;

    private UnitOfWorkFactory unitOfWorkFactory() {
        UnitOfWorkFactory resolved = unitsOfWork;
        if (resolved == null) {
            resolved = axon.getComponent(UnitOfWorkFactory.class);
            unitsOfWork = resolved;
        }
        return resolved;
    }

    private EventStore eventStore() {
        EventStore resolved = store;
        if (resolved == null) {
            resolved = axon.getComponent(EventStore.class);
            store = resolved;
        }
        return resolved;
    }

}
