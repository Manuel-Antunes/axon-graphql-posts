package dev.manuelantunes.axonposts.infrastructure.axon;

import java.time.Clock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * O relógio da aplicação, como bean.
 *
 * <h2>Por que não {@code Instant.now()} direto</h2>
 * Porque é dele que sai o {@code occurredAt} de todo evento, e evento é contrato gravado. Sendo bean, um
 * teste pode substituí-lo por {@link Clock#fixed} e afirmar sobre o carimbo; sendo chamada estática, não
 * pode. É a mesma razão de o projeto Spring tê-lo como {@code @Bean}.
 */
@ApplicationScoped
public class ApplicationClock {

    @Produces
    @Singleton
    public Clock clock() {
        return Clock.systemUTC();
    }
}
