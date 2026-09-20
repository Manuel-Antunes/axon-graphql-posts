package dev.manuelantunes.axonposts.domain.shared;

import java.util.Map;

public interface AggregateIdTypes {
    Map<Class<?>, Class<?>> idTypes();
}
