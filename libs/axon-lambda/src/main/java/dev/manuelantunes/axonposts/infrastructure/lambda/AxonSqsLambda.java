package dev.manuelantunes.axonposts.infrastructure.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * O handler do Lambda movido por SQS — e ele é <b>um só</b>, para os dois serviços.
 *
 * <h2>Por que não há um handler por aplicação</h2>
 * Porque não haveria o que escrever de diferente neles. O que distingue o {@code apps/tagging} do
 * {@code apps/posts-api} na entrada é <i>que fatia cada um ingere</i>, e isso está declarado em dois
 * lugares que já existem: os {@code @Incoming} de cada serviço, e a filter policy da subscription que
 * alimenta a fila daquela função. Um handler por serviço seria este arquivo copiado com outro nome de
 * classe.
 * <p>
 * Isto não contraria a regra de que porta de entrada é apresentação e mora na aplicação: esta classe
 * não é a porta, é o <b>transporte</b> — o lugar equivalente ao conector do RabbitMQ, que também é
 * biblioteca e também não mora em {@code apps/}. A porta continua sendo o {@code @Incoming}.
 *
 * <h2>Por que não é {@code Uni<Void>}</h2>
 * Porque o runtime do Lambda não assina um {@code Uni}. A invocação acaba quando este método retorna e
 * o ambiente de execução é congelado logo depois — devolver um tipo preguiçoso devolveria uma promessa
 * que ninguém vai cumprir. Quem espera de verdade é {@link SqsChannelIngress}, e é por isso que ele
 * espera.
 * <p>
 * (O Funqy aceita {@code Uni}, e nele isto seria exprimível. O preço seria trocar
 * {@code SQSEvent}/{@code SQSBatchResponse} pelos tipos do Funqy e perder o
 * {@code ReportBatchItemFailures}, que é o que impede uma mensagem-veneno de fazer as outras nove do
 * lote voltarem.)
 *
 * <h2>As duas linhas que ligam isto a uma função de verdade</h2>
 * <pre>
 * quarkus.lambda.handler=axon-sqs          # casa com o @Named abaixo
 * AXONPOSTS_LAMBDA_SQS_CHANNEL=post-precreated-in    # variável de ambiente, posta pelo IaC
 * </pre>
 * mais, no event source mapping, {@code FunctionResponseTypes: [ReportBatchItemFailures]} — sem ele a
 * AWS ignora a lista devolvida e o lote continua sendo tudo-ou-nada, <b>sem aviso</b>.
 */
@Named("axon-sqs")
@ApplicationScoped
public class AxonSqsLambda implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private final SqsChannelIngress ingress;

    AxonSqsLambda(SqsChannelIngress ingress) {
        this.ingress = ingress;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        return ingress.ingest(event);
    }
}
