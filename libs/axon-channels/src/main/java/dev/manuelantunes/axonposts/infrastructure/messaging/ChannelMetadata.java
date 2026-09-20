package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.axonframework.messaging.eventstreaming.Tag;

public final class ChannelMetadata {
    public static final String ORIGIN = "axon-channel-origin";

    public static final String TAGS = "axon-channel-tags";

    private ChannelMetadata() {
    }

    public static String encodeTags(List<AxonEventEnvelope.EventTag> tags) {
        StringBuilder encoded = new StringBuilder();
        for (AxonEventEnvelope.EventTag tag : tags) {
            if (!encoded.isEmpty()) {
                encoded.append(';');
            }
            encoded.append(escape(tag.key())).append('=').append(escape(tag.value()));
        }
        return encoded.toString();
    }

    public static Set<Tag> tagsOf(List<AxonEventEnvelope.EventTag> tags) {
        Set<Tag> resolved = new LinkedHashSet<>();
        tags.forEach(tag -> resolved.add(new Tag(tag.key(), tag.value())));
        return resolved;
    }

    public static Set<Tag> decodeTags(String encoded) {
        Set<Tag> tags = new LinkedHashSet<>();
        if (encoded == null || encoded.isBlank()) {
            return tags;
        }
        for (String pair : encoded.split(";")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                tags.add(new Tag(unescape(pair.substring(0, separator)),
                        unescape(pair.substring(separator + 1))));
            }
        }
        return tags;
    }

    private static String escape(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static String unescape(String encoded) {
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
