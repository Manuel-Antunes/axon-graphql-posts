package dev.manuelantunes.axonposts.testing;

import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;

import java.util.ArrayList;
import java.util.List;

public final class RecordingDomainEvents implements DomainEventPublisher {
    private final List<DomainEvent> raised = new ArrayList<>();

    @Override
    public void raise(DomainEvent event) {
        raised.add(event);
    }

    public void clear() {
        raised.clear();
    }

    public List<DomainEvent> raised() {
        return List.copyOf(raised);
    }

    public DomainEvent single() {
        if (raised.size() != 1) {
            throw new AssertionError("esperado exatamente 1 evento, foram " + raised.size() + ": " + raised);
        }
        return raised.getFirst();
    }
}
