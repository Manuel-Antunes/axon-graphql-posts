package dev.manuelantunes.axonposts.infrastructure.messaging;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public record AxonEventEnvelope(
        String messageType,
        String identifier,
        Instant timestamp,
        Map<String, String> metadata,
        List<EventTag> tags,
        String payload) {
    public record EventTag(String key, String value) {
    }

    public static String encodePayload(byte[] payload) {
        return Base64.getEncoder().encodeToString(payload);
    }

    public byte[] decodePayload() {
        return Base64.getMimeDecoder().decode(payload);
    }
}
