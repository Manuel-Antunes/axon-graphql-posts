package dev.manuelantunes.axonposts.infrastructure.time;

import java.time.Clock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
public class ApplicationClock {
    @Produces
    @Singleton
    public Clock clock() {
        return Clock.systemUTC();
    }
}
