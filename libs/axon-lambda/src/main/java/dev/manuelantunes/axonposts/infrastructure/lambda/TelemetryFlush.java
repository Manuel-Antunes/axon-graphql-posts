package dev.manuelantunes.axonposts.infrastructure.lambda;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Esvazia os lotes do OpenTelemetry ANTES de o handler retornar — e sem isto um Lambda curto não tem
 * telemetria nenhuma.
 *
 * <h2>O defeito, medido na stack</h2>
 * O SDK acumula spans e logs num LOTE e o despacha numa thread própria, a cada
 * {@code quarkus.otel.bsp.schedule.delay} (5 s) e {@code blrp.schedule.delay} (1 s). Um Lambda é
 * <b>congelado</b> assim que o handler retorna: aquela thread para de existir até a próxima invocação.
 *
 * <pre>
 * TaggingDecide      REPORT ... Duration: 438.32 ms
 * PostsApiInbox      REPORT ... Duration: 232.81 ms
 * TaggingReplicate   REPORT ... Duration: 195.20 ms
 * </pre>
 *
 * As três terminam muito antes do primeiro despacho, e as três apareciam no backend com zero spans e
 * zero logs — enquanto o coletor da layer subia, rodava e fazia flush sem um único erro, de um buffer
 * vazio.
 *
 * <h2>Por que a layer do coletor NÃO resolve isto sozinha</h2>
 * Porque são dois saltos, e ela só cobre o segundo:
 *
 * <pre>
 * aplicação --(1) OTLP p/ localhost--&gt; coletor (layer) --(2) HTTPS--&gt; backend
 * </pre>
 *
 * A layer recebe o gancho de fim de invocação e despeja o que <b>já está dentro dela</b> — o salto (2),
 * que é o que o {@code decouple} do {@code collector.yaml} garante. Quem decide quando acontece o salto
 * (1) é uma thread dentro do processo da aplicação, que a layer desconhece.
 * <p>
 * Provado nos dois sentidos: depois de uma saga sem telemetria nenhuma, invocar a função com um lote
 * VAZIO fez aparecer o span da invocação anterior, com o carimbo de tempo ORIGINAL. O dado não estava
 * perdido — estava congelado do lado de cá do salto (1).
 *
 * <h2>Por que isso só apareceu com o binário nativo</h2>
 * Porque na JVM o cold start de ~15 s acontecia DENTRO do handler, e o lote disparava no meio dele. A
 * telemetria chegava por uma janela acidental que a lentidão abria — o `tagging` chegou a ter 166 logs
 * no backend. O nativo sobe em 0,6 s, a janela fechou, e o sinal sumiu inteiro.
 *
 * <h2>A ALTERNATIVA ÓBVIA FOI TENTADA, E ELA QUEBRA A SAGA</h2>
 * A extensão tem {@code quarkus.otel.simple} ({@code OTelBuildConfig#simple}), que troca o processador
 * em lote pelo {@code SimpleSpanProcessorWithBatchShutdown}: cada span sai no instante em que termina,
 * sem fila, e o lote passa a ser responsabilidade do {@code batch} do coletor. É a divisão de
 * responsabilidade certa, e foi implantada. <b>A saga parou na versão 1.</b>
 *
 * <pre>
 * ARJUNA012094: Commit of action ... invoked while multiple threads active within it.
 * ARJUNA012107: CheckedAction::check - atomic action ... commiting with 2 threads active!
 * Caused by: java.sql.SQLException: Enlisted connection used without active transaction
 *     at io.agroal.pool.ConnectionHandler.verifyEnlistment
 * </pre>
 *
 * Exportar no {@code onEnd} é exportar DENTRO da transação, e o exportador do Quarkus despacha num
 * contexto Vert.x ({@code executeBlocking} → {@code WorkerTask} nos frames). Esse worker entra na mesma
 * transação, e o Narayana recusa commitar uma ação com duas threads dentro. É a mesma família de falha
 * que o {@code CLAUDE.md} já registra para o {@code @Transactional} na ingestão — lá era "aborting with
 * 2 threads active", aqui é "commiting".
 * <p>
 * <b>E é exatamente por isso que o flush explícito funciona</b>: ele roda depois de a unidade de
 * trabalho ter commitado, na thread do handler, sem transação ativa. Não é a opção que sobrou — é a
 * única das duas que respeita a fronteira transacional que este projeto já pagou para acertar.
 * <p>
 * Baixar {@code bsp.schedule.delay} também não fecha: numa invocação de 195 ms, qualquer valor acima
 * disso continua perdendo, e um valor abaixo paga uma exportação por lote minúsculo em toda invocação.
 *
 * <h2>Onde ele NÃO entra</h2>
 * Nas funções de HTTP e de streaming. A primeira é invocada em sequência, então o lote de uma
 * requisição sai na seguinte, e a segunda mantém o processo vivo enquanto a conexão existir — foram as
 * duas únicas que exportaram durante todo o episódio. Pôr o flush ali trocaria uma exportação por lote
 * por uma exportação por REQUISIÇÃO, que é o custo que o lote existe para evitar.
 */
@ApplicationScoped
public class TelemetryFlush {

    private static final Logger log = LoggerFactory.getLogger(TelemetryFlush.class);

    private final OpenTelemetry openTelemetry;
    private final Duration timeout;

    TelemetryFlush(OpenTelemetry openTelemetry,
            @ConfigProperty(name = "axonposts.lambda.telemetry.flush-timeout",
                    defaultValue = "2s") Duration timeout) {
        this.openTelemetry = openTelemetry;
        this.timeout = timeout;
    }

    /**
     * Despacha o que estiver pendente e ESPERA — bloquear aqui é o ponto.
     *
     * <p>O tempo entra na duração cobrada da invocação, e é isso que se está comprando: a alternativa
     * não é uma invocação mais rápida, é uma invocação sem telemetria. O teto existe para o caso de o
     * coletor não responder — um export travado não pode segurar a resposta da fila até o timeout da
     * função, que derrubaria o lote inteiro de volta.
     *
     * <p>O flush é <b>por provider</b>, e não do SDK: conferido no bytecode da 1.62.0,
     * {@code OpenTelemetrySdk} tem {@code shutdown()} e os três {@code getSdk*Provider()}, e nenhum
     * {@code forceFlush()}. Os três vão juntos porque este projeto exporta os TRÊS sinais —
     * {@code quarkus.otel.logs.enabled} e {@code metrics.enabled} estão ligados nos dois serviços.
     *
     * <p>Nunca lança. Telemetria que falha não pode transformar uma mensagem processada com sucesso
     * numa mensagem que volta para a fila.
     */
    public void beforeFreeze() {
        if (!(openTelemetry instanceof OpenTelemetrySdk sdk)) {
            // Acontece com o SDK desligado (`quarkus.otel.sdk.disabled`), quando o bean é o no-op da
            // API. Não é erro: é a configuração dizendo que não há o que despachar.
            log.debug("telemetria: o bean OpenTelemetry não é o SDK ({}); nada a despachar",
                    openTelemetry.getClass().getName());
            return;
        }
        try {
            boolean completed = CompletableResultCode.ofAll(List.of(
                    sdk.getSdkTracerProvider().forceFlush(),
                    sdk.getSdkLoggerProvider().forceFlush(),
                    sdk.getSdkMeterProvider().forceFlush()))
                    .join(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .isSuccess();
            if (!completed) {
                log.warn("telemetria: o flush não terminou em {} — o que sobrou sai na próxima "
                        + "invocação desta função", timeout);
            }
        } catch (RuntimeException failure) {
            log.warn("telemetria: o flush falhou; a invocação segue normalmente", failure);
        }
    }
}
