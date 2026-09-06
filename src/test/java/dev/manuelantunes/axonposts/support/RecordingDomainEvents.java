package dev.manuelantunes.axonposts.support;

import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.shared.DomainEventPublisher;

import java.util.ArrayList;
import java.util.List;

/**
 * Duplo de teste da porta {@link DomainEventPublisher}: só guarda o que o domínio disparou.
 * <p>
 * É a prova de que a porta faz o que promete — dá para exercitar as decisões do {@code Post} sem Axon,
 * sem Spring e sem event store, e ainda assim afirmar exatamente quais eventos foram disparados.
 */
public final class RecordingDomainEvents implements DomainEventPublisher {

    private final List<DomainEvent> raised = new ArrayList<>();

    @Override
    public void raise(DomainEvent event) {
        raised.add(event);
    }

    public List<DomainEvent> raised() {
        return List.copyOf(raised);
    }

    /** O único evento disparado; falha se foram zero ou mais de um. */
    public DomainEvent single() {
        if (raised.size() != 1) {
            throw new AssertionError("esperado exatamente 1 evento, foram " + raised.size() + ": " + raised);
        }
        return raised.getFirst();
    }
}
