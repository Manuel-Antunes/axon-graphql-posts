package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * O {@code Connection} da GraphQL Cursor Connections Specification, <b>uma vez só</b>.
 *
 * <h2>Dois parâmetros de tipo, e o segundo não é redundância</h2>
 * {@code N} é o nó; {@code E} é o tipo <b>concreto</b> do edge. Ter os dois é o que faz o schema sair com
 * {@code edges: [PostEdge]!} em vez de {@code edges: [Edge_Post]!}: se o campo fosse declarado como
 * {@code List<Edge<N>>}, o construtor de schema veria um tipo genérico instanciado ali dentro e o nomearia
 * pelo sufixo, desfazendo justamente o que a subclasse concreta conseguiu. Declarado como {@code List<E>},
 * o {@code E} resolve para {@code PostEdge} — uma classe comum, com nome comum.
 * <p>
 * O {@code extends Edge<N>} no parâmetro não é decorativo: é ele que impede
 * {@code Connection<PostView, TagEdge>} de compilar.
 *
 * <h2>Uso</h2>
 * <pre>{@code
 * public final class PostEdge extends Edge<PostView> { }
 * public final class PostConnection extends Connection<PostView, PostEdge> { }
 *
 * // no resolver:
 * return Connections.page(views, args, PostEdge::new, PostConnection::new);
 * }</pre>
 * Duas declarações de uma linha por tipo paginado, e nenhuma lógica de paginação duplicada: cursor,
 * {@code pageInfo} e recorte vivem em {@link Connections}, que é genérico de verdade.
 *
 * @param <N> o tipo do nó
 * @param <E> o tipo concreto do edge, que dá nome ao tipo no schema
 */
/**
 * REGISTRO PARA REFLEXÃO — e por que a subclasse não basta.
 *
 * <p>No schema quem existe é {@code PostConnection}, e o Quarkus registra os tipos do schema sozinho.
 * Só que {@code PostConnection} é uma subclasse VAZIA: {@code getEdges()} e {@code getPageInfo()} são
 * declarados aqui, na base genérica, e um método herdado não vem no registro da subclasse.
 *
 * <p>Na JVM isso nunca aparece. No binário nativo, a leitura de QUALQUER campo da connection falha —
 * e falha em silêncio: o cliente recebe {@code "System error"} com {@code path: ["posts","edges"]} e
 * o servidor não loga uma linha. Medido: {@code pageInfo} e {@code edges} falhando juntos, enquanto
 * {@code __typename} e o SDL respondiam normalmente. É o que denuncia que o problema é o OBJETO, e
 * não um campo.
 *
 * <p>É o preço do mecanismo dos genéricos descrito abaixo: a subclasse de uma linha existe para dar
 * ao schema o nome da convenção Relay, e é justamente ela que esvazia o registro.
 */
@RegisterForReflection
public abstract class Connection<N, E extends Edge<N>> {

    private List<E> edges = List.of();
    private PageInfo pageInfo = PageInfo.empty(false);

    protected Connection() {
    }

    /**
     * Pacote-visível: quem monta uma connection é {@link Connections}. Vale o mesmo que em
     * {@link Edge#init}: de fora de {@code relay} ela é somente leitura.
     */
    void init(List<E> edges, PageInfo pageInfo) {
        this.edges = List.copyOf(edges);
        this.pageInfo = pageInfo;
    }

    @NonNull
    @Description("Os itens desta página, cada um com a sua posição")
    public List<E> getEdges() {
        return edges;
    }

    @NonNull
    @Description("Se existe página seguinte/anterior, e os cursores das pontas desta")
    public PageInfo getPageInfo() {
        return pageInfo;
    }
}
