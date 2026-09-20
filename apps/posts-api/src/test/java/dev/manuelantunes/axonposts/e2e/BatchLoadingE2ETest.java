package dev.manuelantunes.axonposts.e2e;

import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import io.quarkus.test.junit.QuarkusTest;
import dev.manuelantunes.axonposts.support.GraphQl;
import dev.manuelantunes.axonposts.support.Realm;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class BatchLoadingE2ETest extends AbstractGraphQlE2ETest {
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

    private long statementsFor(int n, int expectedPosts) {
        return cheapestStatementCount(() ->
                assertThat(anonymous.execute(FAT_QUERY, "n", n).list("posts.edges")).hasSize(expectedPosts));
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

        assertThat(forFive)
                .as("N+1: 5 posts custaram %d statements contra %d de 1 post", forFive, forOne)
                .isEqualTo(forOne);
    }

    @Test
    void severalAuthorsInOneResponseStillCostOneQueryEach() {
        GraphQl author = asAuthor();
        author.createPost("Do mesmo autor 1", "c");
        author.createPost("Do mesmo autor 2", "c");
        long oneAuthor = statementsFor(5, 2);

        resetDatabase();

        asAuthor().createPost("Do primeiro autor", "c");
        as(Realm.PROMOTED_USERNAME).createPost("Do segundo autor", "c");
        long twoAuthors = statementsFor(5, 2);

        assertThat(twoAuthors)
                .as("dois autores custaram %d statements contra %d de um autor", twoAuthors, oneAuthor)
                .isEqualTo(oneAuthor);
    }

    @Test
    void theResponseIsActuallyCorrectAndNotJustCheap() {
        GraphQl author = asAuthor();
        author.createPost("Alfa", "c");
        author.createPost("Beta", "c");

        var response = anonymous.execute(FAT_QUERY, "n", 5);

        assertThat(response.string("posts.edges[0].node.tags.edges[0].node.name")).isEqualTo("Untagged");
        assertThat(response.string("posts.edges[0].node.author.email")).isEqualTo(Realm.AUTHOR_USERNAME);
        assertThat(response.list("posts.edges[1].node.author.posts.edges")).hasSize(2);
    }
}
