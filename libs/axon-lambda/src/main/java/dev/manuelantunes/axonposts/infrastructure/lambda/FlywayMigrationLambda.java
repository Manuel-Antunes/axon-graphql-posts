package dev.manuelantunes.axonposts.infrastructure.lambda;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

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
        String summary = result.migrationsExecuted == 0
                ? "nada a fazer: o schema já está na versão " + result.initialSchemaVersion
                : result.migrationsExecuted + " migration(s) aplicada(s), agora na versão "
                        + result.targetSchemaVersion;
        log.info("flyway: {}", summary);

        telemetry.beforeFreeze();
        return summary;
    }
}
