package dev.manuelantunes.axonposts.infrastructure.messaging.aws;

import java.util.LinkedHashMap;
import java.util.Map;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;

import dev.manuelantunes.axonposts.infrastructure.messaging.EventAddress;

public final class AwsEventAttributes {
    public static final String MESSAGE_NAME = "axon-message-name";

    public static final String ROUTING_KEY = "axon-routing-key";

    public static final String MESSAGE_TYPE = "axon-message-type";

    public static final String MESSAGE_ID = "axon-message-id";

    public static final String NAMESPACE = "axon-namespace";

    private AwsEventAttributes() {
    }

    public static Map<String, String> of(EventAddress address) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(MESSAGE_NAME, localName(address));
        attributes.put(ROUTING_KEY, routingKey(address));
        attributes.put(MESSAGE_TYPE, address.messageType());
        attributes.put(MESSAGE_ID, address.identifier());
        attributes.put(NAMESPACE, address.namespace());
        inject(attributes);
        return attributes;
    }

    private static void inject(Map<String, String> attributes) {
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(Context.current(), attributes, (carrier, key, value) -> {
                    if (carrier != null) {
                        carrier.put(key, value);
                    }
                });
    }

    public static String routingKey(EventAddress address) {
        return address.qualifiedName() + "." + address.orderingKey();
    }

    public static String localName(EventAddress address) {
        String qualified = address.qualifiedName();
        String prefix = address.namespace() + ".";
        return qualified.startsWith(prefix) ? qualified.substring(prefix.length()) : qualified;
    }
}
