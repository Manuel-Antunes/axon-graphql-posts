package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

@ApplicationScoped
public class MessageInbox {
    private static final String RECORD = """
            insert into axon_message_inbox (identifier, message_type, origin, received_at)
            values (?1, ?2, ?3, ?4)
            on conflict (identifier) do nothing
            """;

    private final EntityManager em;
    private final Clock clock;

    MessageInbox(EntityManager em, Clock clock) {
        this.em = em;
        this.clock = clock;
    }

    public boolean register(String identifier, String messageType, String origin) {
        return em.createNativeQuery(RECORD)
                .setParameter(1, identifier)
                .setParameter(2, messageType)
                .setParameter(3, origin)
                .setParameter(4, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .executeUpdate() == 1;
    }
}
