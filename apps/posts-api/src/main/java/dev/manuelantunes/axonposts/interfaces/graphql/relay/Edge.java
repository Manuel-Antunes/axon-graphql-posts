package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * O {@code Edge} da GraphQL Cursor Connections Specification, <b>uma vez só</b>: o nó e a posição dele
 * na conexão.
 *
 * <h2>Como isto vira {@code PostEdge} no schema</h2>
 * Um tipo genérico instanciado vira, no SmallRye, {@code Edge_Post} — o nome do tipo mais um sufixo por
 * argumento. É legal, é estável, e não é o que a convenção Relay manda; um cliente gerado a partir desse
 * schema não se parece com nenhum outro cliente Relay do mundo.
 * <p>
 * A saída é uma <b>subclasse concreta</b>:
 * <pre>{@code
 * public final class PostEdge extends Edge<PostView> { }
 * }</pre>
 * Uma classe sem parâmetros de tipo próprios é, para o construtor de schema, um tipo comum — e um tipo
 * comum leva o nome da classe. O que ele <i>não</i> perde é a resolução dos genéricos: o SmallRye sobe a
 * hierarquia, encontra {@code Edge<PostView>} e resolve {@code N} para {@code PostView} ao montar o campo
 * {@code node}. Uma linha por tipo paginado, e o schema sai com {@code PostEdge}, {@code TagEdge},
 * {@code PostConnection}.
 * <p>
 * É o mais perto que dá para chegar do {@code ConnectionTypeDefinitionConfigurer} do Spring, que gera os
 * tipos a partir do sufixo {@code Connection} do campo. A diferença é uma declaração de uma linha, e em
 * troca ela é visível: o tipo existe em Java, o compilador o confere, e o IDE navega até ele.
 *
 * <h2>Por que classe mutável, e não record</h2>
 * Record não pode estender nada, e a subclasse concreta é justamente o que dá nome ao tipo. O preço são
 * dois campos não-finais preenchidos por {@link #init}, que é pacote-visível: só {@link Connections}
 * constrói um edge, e de fora ele é somente leitura.
 *
 * @param <N> o tipo do nó — o DTO que aparece no schema
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
public abstract class Edge<N> {

    private N node;
    private String cursor;

    protected Edge() {
    }

    /**
     * Pacote-visível: quem monta um edge é {@link Connections}. As subclasses concretas
     * ({@link PostEdge}, {@link TagEdge}) moram neste mesmo pacote, então a barreira é contra o resto do
     * projeto — de fora de {@code relay}, um edge é somente leitura.
     */
    void init(N node, String cursor) {
        this.node = node;
        this.cursor = cursor;
    }

    @NonNull
    @Description("O item desta posição da conexão")
    public N getNode() {
        return node;
    }

    @NonNull
    @Description("Posição opaca deste item; devolva-a em `after` para continuar daqui")
    public String getCursor() {
        return cursor;
    }
}
