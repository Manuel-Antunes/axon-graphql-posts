package dev.manuelantunes.axonposts.e2e;

import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import dev.manuelantunes.axonposts.support.KeycloakContainerConfig;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Os DataLoaders: uma resposta com N posts custa o mesmo que uma com 1.
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
 * A query pede tudo de uma vez: as tags de cada post ({@code @SchemaMapping} + DataLoader), o e-mail do
 * autor ({@code @BatchMapping} na interface {@code User}) e os outros posts do autor
 * ({@code Author.posts}). São três funções de lote distintas, e nenhuma pode virar N+1.
 */
class BatchLoadingE2ETest extends AbstractGraphQlE2ETest {

    /** Pede tudo o que é resolvido à parte: tags, e-mail do autor e os posts dele. */
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

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** Executa a consulta pedindo {@code n} posts e devolve quantos statements o Hibernate preparou. */
    private long statementsFor(int n, int expectedPosts) {
        Statistics statistics = statistics();
        statistics.clear();

        anonymous.document(FAT_QUERY)
                .variable("n", n)
                .execute()
                .path("posts.edges").entityList(Object.class).hasSize(expectedPosts);

        return statistics.getPrepareStatementCount();
    }

    @Test
    void theQueryCountDoesNotGrowWithTheNumberOfPosts() {
        HttpGraphQlTester author = asAuthor();
        for (int i = 1; i <= 5; i++) {
            createPost(author, "Post " + i, "conteúdo");
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
        HttpGraphQlTester author = asAuthor();
        createPost(author, "Do mesmo autor 1", "c");
        createPost(author, "Do mesmo autor 2", "c");
        long oneAuthor = statementsFor(5, 2);

        jdbc.execute("truncate table post_tags, posts, tags, accounts, authors, users cascade");

        // os mesmos dois posts, agora de autores diferentes
        createPost(asAuthor(), "Do primeiro autor", "c");
        createPost(as(KeycloakContainerConfig.PROMOTED_USERNAME), "Do segundo autor", "c");
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
        HttpGraphQlTester author = asAuthor();
        createPost(author, "Alfa", "c");
        createPost(author, "Beta", "c");

        anonymous.document(FAT_QUERY).variable("n", 5).execute()
                .path("posts.edges[0].node.tags.edges[0].node.name").entity(String.class).isEqualTo("Untagged")
                .path("posts.edges[0].node.author.email").entity(String.class).isEqualTo("manuel@example.com")
                .path("posts.edges[1].node.author.posts.edges").entityList(Object.class).hasSize(2);
    }
}
