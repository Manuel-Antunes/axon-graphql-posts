package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import dev.manuelantunes.axonposts.interfaces.graphql.error.BadRequestException;

/**
 * Os argumentos de paginação ({@code first}/{@code after}) já validados e traduzidos para o par
 * {@code offset}/{@code limit} que o resto da aplicação entende.
 *
 * <h2>Onde esta tradução mora, e por quê</h2>
 * Na camada de interface, porque é aqui que a Relay connection existe: nem o domínio nem a aplicação
 * conhecem cursor. As mensagens do query bus carregam {@code offset} e {@code limit} crus — mensagem não
 * carrega tipo de framework, e a próxima fronteira que essa query atravessar pode não ser GraphQL.
 *
 * <h2>Regras adotadas (a spec deixa em aberto; aqui são explícitas)</h2>
 * <ul>
 *   <li>sem {@code first} → {@link #DEFAULT_LIMIT};</li>
 *   <li>limite máximo por página, para o cliente não pedir 10.000 linhas de uma vez. A versão Spring não
 *       tinha esse teto: {@code subrange.count()} ia direto para o {@code Limit} do Spring Data;</li>
 *   <li>{@code first} negativo é erro, e não uma página vazia silenciosa;</li>
 *   <li>só paginação para frente. A ordenação do read model é por criação e o schema declara apenas
 *       {@code first}/{@code after} — aceitar {@code last}/{@code before} exigiria uma segunda cláusula e
 *       um segundo caminho de teste para uma capacidade que nenhum cliente pede aqui.</li>
 * </ul>
 *
 * @param offset índice da primeira linha desta página, contando de 0
 * @param limit  quantas linhas o cliente pediu
 * @param type   prefixo do cursor desta conexão; ver {@link Cursors}
 */
public record ConnectionArgs(long offset, int limit, String type) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    /**
     * Valida e normaliza os argumentos do campo.
     * <p>
     * A convenção do cursor é a da spec: {@code after} aponta para a <b>última linha já vista</b>, então
     * a página seguinte começa em {@code offset + 1}.
     *
     * @param type  prefixo do cursor desta conexão (por exemplo {@code "post"})
     * @param first quantos itens trazer; {@code null} = {@link #DEFAULT_LIMIT}
     * @param after cursor do último item já visto; {@code null} = do começo
     */
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

    /** {@code true} se esta página não é a primeira — o que a spec chama de {@code hasPreviousPage}. */
    public boolean hasPreviousPage() {
        return offset > 0;
    }
}
