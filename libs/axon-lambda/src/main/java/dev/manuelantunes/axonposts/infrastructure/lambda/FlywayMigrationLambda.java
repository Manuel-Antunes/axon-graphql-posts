package dev.manuelantunes.axonposts.infrastructure.lambda;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * Cria o schema. É um passo de DEPLOY, invocado à mão, e não um efeito colateral da partida.
 *
 * <h2>Por que isto existe, se o Flyway já está no classpath</h2>
 * Porque {@code quarkus.flyway.migrate-at-start} é {@code false}, e continua sendo — pela razão medida
 * que está escrita no {@code application.properties}: {@code AxonExtension.init} é um recorder de
 * RUNTIME_INIT que toca o EntityManager antes de o Flyway ter a vez, e com
 * {@code schema-management.strategy=validate} contra banco vazio a aplicação morre com
 * {@code missing table [accounts]}.
 * <p>
 * Em Lambda essa decisão deixa de ser um contorno e vira a única correta. Migration na partida de uma
 * função que escala para N ambientes de execução seria N tentativas concorrentes de alterar o mesmo
 * schema — o Flyway tem lock, então o que aconteceria não é corrupção: é cada cold start esperando o
 * lock de outro, dentro do timeout da invocação que o usuário está esperando.
 * <p>
 * Quem rodava isto no compose eram os serviços {@code flyway-*}. Aqui é uma invocação:
 *
 * <pre>
 * aws lambda invoke --function-name &lt;stack&gt;-PostsMigrate /dev/stdout
 * </pre>
 *
 * <h2>Por que é o MESMO zip das outras funções</h2>
 * Porque {@code quarkus.lambda.handler} é configuração de RUNTIME. O artefato já contém o Flyway, as
 * migrations e o datasource; mudar a variável de ambiente
 * {@code QUARKUS_LAMBDA_HANDLER=flyway-migrate} é tudo que separa esta função da que consome a fila.
 * Um zip a menos para construir, e nenhuma chance de as migrations empacotadas divergirem das que a
 * aplicação valida.
 *
 * <h2>O retorno</h2>
 * Uma linha legível, porque quem a lê é uma pessoa olhando a saída de {@code aws lambda invoke} — e
 * "já estava migrado" precisa ser distinguível de "migrou agora" sem abrir o CloudWatch.
 */
@Named("flyway-migrate")
@ApplicationScoped
public class FlywayMigrationLambda implements RequestHandler<Object, String> {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationLambda.class);

    private final Flyway flyway;
    private final TelemetryFlush telemetry;

    FlywayMigrationLambda(Flyway flyway, TelemetryFlush telemetry) {
        this.flyway = flyway;
        this.telemetry = telemetry;
    }

    @Override
    public String handleRequest(Object ignored, Context context) {
        MigrateResult result = flyway.migrate();
        // `targetSchemaVersion` é NULL quando nada foi executado — o Flyway só o preenche quando há
        // alvo a alcançar. Lido sem cuidado, o relatório dizia "já está na versão null", que soa como
        // banco vazio e é o oposto do que aconteceu. Quem responde nesse caso é `initialSchemaVersion`.
        String summary = result.migrationsExecuted == 0
                ? "nada a fazer: o schema já está na versão " + result.initialSchemaVersion
                : result.migrationsExecuted + " migration(s) aplicada(s), agora na versão "
                        + result.targetSchemaVersion;
        log.info("flyway: {}", summary);

        // Esta função roda UMA vez por deploy e o sandbox dela congela em seguida, para
        // sempre — sem o flush, o relatório do Flyway não chega ao backend nunca. É o caso
        // extremo do que `TelemetryFlush` descreve.
        telemetry.beforeFreeze();
        return summary;
    }
}
