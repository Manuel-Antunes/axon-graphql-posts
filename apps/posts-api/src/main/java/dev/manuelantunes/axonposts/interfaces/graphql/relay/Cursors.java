package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import dev.manuelantunes.axonposts.interfaces.graphql.error.BadRequestException;

public final class Cursors {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String SEPARATOR = ":";

    private Cursors() {
    }

    public static String encode(String type, long offset) {
        String raw = type + SEPARATOR + offset;
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static long decode(String type, String cursor) {
        try {
            String raw = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
            String prefix = type + SEPARATOR;
            if (!raw.startsWith(prefix)) {
                throw new IllegalArgumentException("prefixo inesperado");
            }
            long offset = Long.parseLong(raw.substring(prefix.length()));
            if (offset < 0) {
                throw new IllegalArgumentException("posição negativa");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("cursor inválido: " + cursor);
        }
    }
}
