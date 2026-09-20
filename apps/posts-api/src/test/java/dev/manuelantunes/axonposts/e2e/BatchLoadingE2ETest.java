package dev.manuelantunes.axonposts.e2e;

import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import io.quarkus.test.junit.QuarkusTest;
import dev.manuelantunes.axonposts.support.GraphQl;
import dev.manuelantunes.axonposts.support.Realm;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Os lotes: uma resposta com N posts custa o mesmo que uma com 1.
 *
 * <h2>Como se prova um lote sem contar linha de log</h2>
 * A tentação é contar as mensagens de {@code log.debug("lote de tags: N post(s)")}. Isso prova que a
 * função de lote foi chamada uma vez — não prova que ela <b>não</b> foi chamada N vezes por outro caminho,
 * e quebra quando alguém mexe no texto do log.
 * <p>
 * O que prova de verdade é a estatística do Hibernate: contar os {@code PreparedStatement} de uma
 * resposta com 1 post e de outra com 5, <b>na mesma consulta</b>. Se o lote funciona, os dois números são
 * iguais; se um N+1 voltar, o segundo cresce com o número de posts. A asserção é sobre a propriedade que
 * importa — o custo não acompanhar o tamanho do resultado — e não sobre um número mágico que muda a cada
 * ajuste de mapeamento.
 *
 * <h2>Os três lotes da mesma resposta</h2>
 * A query pede tudo de uma vez: as tags de cada post, o autor completo e os outros posts do autor. São
 * três métodos {@code @Source List<T>} distintos, e nenhum pode virar N+1.
 * <p>
 * No projeto Spring os três eram DataLoaders registrados à mão no {@code BatchLoaderRegistry}, porque o
 * {@code @BatchMapping} não enxerga argumentos de campo e dois deles são paginados. Aqui são três métodos
 * anotados — o que este teste mede é que a simplificação não custou o lote.
 */
@QuarkusTest
class BatchLoadingE2ETest extends AbstractGraphQlE2ETest {

    /** Pede tudo o que é resolvido à parte: tags, autor completo e os posts dele. */
    private static final String FAT_QUERY = """
            query Tudo($n: Int!) {
              posts(first: $n) {
                edges { node {
                  title
                  tags(first: 3) { edges { node { name } } }
                  author { id name email posts(first: 3) { edges { node { title } } } }
                } }
              }
            }""";

    /**
     * Executa a consulta pedindo {@code n} posts e devolve quantos statements o Hibernate preparou.
     * <p>
     * A janela de medição começa em {@code statisticsOfAQuietDatabase()}, e não num {@code clear()}
     * solto: o processor que avisa os assinantes é assíncrono e conta na MESMA estatística. Ver o
     * Javadoc daquele método.
     */
    private long statementsFor(int n, int expectedPosts) {
        Statistics statistics = statisticsOfAQuietDatabase();

        assertThat(anonymous.execute(FAT_QUERY, "n", n).list("posts.edges")).hasSize(expectedPosts);

        return statistics.getPrepareStatementCount();
    }

    @Test
    void theQueryCountDoesNotGrowWithTheNumberOfPosts() {
        GraphQl author = asAuthor();
        for (int i = 1; i <= 5; i++) {
            author.createPost("Post " + i, "conteúdo");
        }

        long forOne = statementsFor(1, 1);
        long forFive = statementsFor(5, 5);

        assertThat(forOne)
                .as("uma resposta com 1 post precisa de pelo menos a consulta dos posts")
                .isPositive();

        // é a asserção inteira: cinco vezes mais posts, o mesmo número de idas ao banco
        assertThat(forFive)
                .as("N+1: 5 posts custaram %d statements contra %d de 1 post", forFive, forOne)
                .isEqualTo(forOne);
    }

    @Test
    void severalAuthorsInOneResponseStillCostOneQueryEach() {
        // dois posts, um autor
        GraphQl author = asAuthor();
        author.createPost("Do mesmo autor 1", "c");
        author.createPost("Do mesmo autor 2", "c");
        long oneAuthor = statementsFor(5, 2);

        // `resetDatabase()` e não um truncate solto: ele restaura a SEMENTE junto (a tag padrão vem da
        // migration V5). Um truncate cru aqui apagava a tag e o próximo `createPost` falhava com
        // `Tag não encontrada` — um erro que não tem nada a ver com o que este teste mede.
        resetDatabase();

        // os mesmos dois posts, agora de autores diferentes
        asAuthor().createPost("Do primeiro autor", "c");
        as(Realm.PROMOTED_USERNAME).createPost("Do segundo autor", "c");
        long twoAuthors = statementsFor(5, 2);

        // com dois autores os lotes recebem duas chaves em vez de uma — e continuam sendo uma consulta
        // cada. Comparar as duas medições evita amarrar o teste a um número que muda com o mapeamento.
        assertThat(twoAuthors)
                .as("dois autores custaram %d statements contra %d de um autor", twoAuthors, oneAuthor)
                .isEqualTo(oneAuthor);
    }

    @Test
    void theResponseIsActuallyCorrectAndNotJustCheap() {
        // um lote quebrado que devolvesse vazio também seria barato: o conteúdo precisa bater
        GraphQl author = asAuthor();
        author.createPost("Alfa", "c");
        author.createPost("Beta", "c");

        var response = anonymous.execute(FAT_QUERY, "n", 5);

        assertThat(response.string("posts.edges[0].node.tags.edges[0].node.name")).isEqualTo("Untagged");
        assertThat(response.string("posts.edges[0].node.author.email")).isEqualTo(Realm.AUTHOR_USERNAME);
        assertThat(response.list("posts.edges[1].node.author.posts.edges")).hasSize(2);
    }
}
