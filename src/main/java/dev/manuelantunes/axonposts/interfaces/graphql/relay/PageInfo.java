package dev.manuelantunes.axonposts.interfaces.graphql.relay;

/**
 * Exatamente o {@code PageInfo} da GraphQL Cursor Connections Specification.
 * <p>
 * Os booleanos são primitivos de propósito: o SmallRye expõe primitivo como {@code Boolean!} sem
 * precisar de anotação. {@code startCursor}/{@code endCursor} ficam {@code null} numa página vazia, e por
 * isso são os únicos campos anuláveis da spec.
 * <p>
 * Não é genérico, então o nome no schema é o da classe: {@code PageInfo}, um só para todas as
 * connections. É o mesmo tipo compartilhado que o {@code ConnectionTypeDefinitionConfigurer} do Spring
 * gerava uma vez e reusava.
 */
public record PageInfo(
        boolean hasNextPage,
        boolean hasPreviousPage,
        String startCursor,
        String endCursor) {

    /** A página vazia: não há cursor de início nem de fim porque não há item nenhum. */
    static PageInfo empty(boolean hasPreviousPage) {
        return new PageInfo(false, hasPreviousPage, null, null);
    }
}
