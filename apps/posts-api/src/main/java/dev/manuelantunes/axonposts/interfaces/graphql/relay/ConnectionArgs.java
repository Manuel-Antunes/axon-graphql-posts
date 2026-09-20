package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import dev.manuelantunes.axonposts.interfaces.graphql.error.BadRequestException;

public record ConnectionArgs(long offset, int limit, String type) {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public static ConnectionArgs of(String type, Integer first, String after) {
        int limit = first == null ? DEFAULT_LIMIT : first;
        if (limit < 0) {
            throw new BadRequestException("'first' não pode ser negativo");
        }
        if (limit > MAX_LIMIT) {
            throw new BadRequestException("'first' deve ser no máximo " + MAX_LIMIT);
        }
        long offset = after == null || after.isBlank() ? 0 : Cursors.decode(type, after) + 1;
        return new ConnectionArgs(offset, limit, type);
    }

    public boolean hasPreviousPage() {
        return offset > 0;
    }
}
