package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import io.smallrye.graphql.api.federation.Shareable;

/**
 * As pontas de uma página, na forma que a especificação Relay pede.
 *
 * <h2>{@code @Shareable}: o único tipo deste schema que outro subgraph também vai definir</h2>
 * Na Federação 2 um campo pertence a <b>um</b> subgraph, e a composição falha quando dois definem o
 * mesmo. {@code PageInfo} é a exceção estrutural: ele não é entidade nem tem dono — é a forma de uma
 * página, e todo subgraph que pagine escreve a sua. O {@code @shareable} é o que diz ao roteador que
 * essas definições são a mesma coisa e podem coexistir.
 * <p>
 * {@code PostConnection}, {@code PostEdge} e as irmãs <b>não</b> levam a anotação de propósito: elas
 * carregam {@code Post} e {@code Tag}, que são entidades daqui, então nenhum outro subgraph tem como
 * defini-las sem primeiro ter os tipos — e se tivesse, seria um conflito de verdade, que é justamente o
 * que a composição deve recusar.
 */
@Shareable
public record PageInfo(
        boolean hasNextPage,
        boolean hasPreviousPage,
        String startCursor,
        String endCursor) {

    static PageInfo empty(boolean hasPreviousPage) {
        return new PageInfo(false, hasPreviousPage, null, null);
    }
}
