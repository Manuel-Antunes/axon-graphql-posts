package dev.manuelantunes.axonposts.e2e;

import java.util.List;
import java.util.Map;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import dev.manuelantunes.axonposts.support.GraphQl;
import dev.manuelantunes.axonposts.support.Realm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O {@code _entities} de verdade: o que o roteador faz quando outro subgraph só tem a chave.
 *
 * <h2>O que este teste prova que o SDL não prova</h2>
 * {@code @key} no SDL é uma <b>promessa</b>: a composição a aceita sem nunca chamar ninguém. Quem paga a
 * promessa é o {@code @Resolver}, e o casamento entre os dois — tipo de retorno e conjunto de nomes de
 * argumento — acontece em <b>runtime</b>, dentro do {@code FederationDataFetcher}. Renomear o argumento
 * {@code id} compila, passa no {@code FederationSchemaTest} e quebra aqui. É o único lugar onde quebra.
 *
 * <h2>Ordem e tamanho são o contrato, não detalhe</h2>
 * O lote entrega uma lista e o roteador casa <b>por posição</b> com as representações que mandou. Por
 * isso os testes de ordem e de id inexistente não são zelo: são a asserção do contrato.
 */
@QuarkusTest
class FederationEntitiesE2ETest extends AbstractGraphQlE2ETest {

    private static final String ENTITIES = """
            query Entidades($reps: [_Any!]!) {
              _entities(representations: $reps) {
                __typename
                ... on Post { id title version }
                ... on Tag { id name }
                ... on Author { id email bio }
                ... on Reader { id email }
              }
            }""";

    @Inject
    EntityManagerFactory entityManagerFactory;

    private static Map<String, Object> ref(String typename, String id) {
        return Map.of("__typename", typename, "id", id);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resolve(Map<String, Object>... representations) {
        return (List<Map<String, Object>>) anonymous
                .execute(ENTITIES, "reps", List.of(representations))
                .list("_entities");
    }

    @Test
    void aPostIsReachableByItsKeyAlone() {
        String id = asAuthor().createPost("Federado", "conteúdo");

        List<Map<String, Object>> entities = resolve(ref("Post", id));

        assertThat(entities).hasSize(1);
        assertThat(entities.getFirst())
                .containsEntry("__typename", "Post")
                .containsEntry("id", id)
                .containsEntry("title", "Federado")
                // versão 2: o post nasce com a tag padrão, e o _entities lê o mesmo read model
                .containsEntry("version", 2);
    }

    @Test
    void theAnswerComesBackInTheOrderTheRepresentationsWereSent() {
        GraphQl author = asAuthor();
        String primeiro = author.createPost("Primeiro", "c");
        String segundo = author.createPost("Segundo", "c");

        // de propósito fora da ordem de criação: quem manda na ordem é a lista de representações
        List<Map<String, Object>> entities = resolve(ref("Post", segundo), ref("Post", primeiro));

        assertThat(entities).map(entity -> entity.get("title")).containsExactly("Segundo", "Primeiro");
    }

    @Test
    void anEntityThatDoesNotExistIsNullInItsOwnPosition() {
        GraphQl author = asAuthor();
        String existe = author.createPost("Existe", "c");
        String sumiu = "00000000-0000-0000-0000-000000000000";

        List<Map<String, Object>> entities = resolve(ref("Post", sumiu), ref("Post", existe), ref("Post", sumiu));

        // o buraco fica NO LUGAR dele: uma lista de tamanho 2 deslocaria tudo o que o roteador anexa
        assertThat(entities).hasSize(3);
        assertThat(entities.get(0)).isNull();
        assertThat(entities.get(1)).containsEntry("title", "Existe");
        assertThat(entities.get(2)).isNull();
    }

    @Test
    void severalTypesTravelInTheSameCall() {
        String postId = asAuthor().createPost("Com tag", "c");
        String tagId = anonymous.execute("""
                query($id: ID!) { post(id: $id) { tags(first: 1) { edges { node { id } } } } }""",
                "id", postId).string("post.tags.edges[0].node.id");
        String authorId = anonymous.execute("""
                query($id: ID!) { post(id: $id) { author { id } } }""", "id", postId)
                .string("post.author.id");

        List<Map<String, Object>> entities =
                resolve(ref("Tag", tagId), ref("Post", postId), ref("Author", authorId));

        // as representações são agrupadas por tipo para ir em lote, e reordenadas de volta na saída
        assertThat(entities).map(entity -> entity.get("__typename"))
                .containsExactly("Tag", "Post", "Author");
        assertThat(entities.get(0)).containsEntry("name", "Untagged");
        assertThat(entities.get(2)).containsEntry("email", Realm.AUTHOR_USERNAME);
    }

    @Test
    void askingForTheWrongConcreteTypeAnswersNullAndNotTheOtherType() {
        String authorId = asAuthor().execute("{ me { id } }").string("me.id");
        String readerId = asReader().execute("{ me { id } }").string("me.id");

        List<Map<String, Object>> entities = resolve(
                ref("Reader", authorId),   // é Author: não é um Reader
                ref("Author", readerId),   // é Reader: não é um Author
                ref("Reader", readerId),
                ref("Author", authorId));

        assertThat(entities.get(0)).as("um Author devolvido como Reader seria uma resposta inconsistente").isNull();
        assertThat(entities.get(1)).isNull();
        assertThat(entities.get(2)).containsEntry("email", Realm.READER_USERNAME);
        assertThat(entities.get(3)).containsEntry("email", Realm.AUTHOR_USERNAME);
    }

    @Test
    void theInterfaceKeyResolvesToTheConcreteType() {
        // é a representação que um subgraph com @interfaceObject manda: ele só conhece User
        String authorId = asAuthor().execute("{ me { id } }").string("me.id");

        List<Map<String, Object>> entities = resolve(ref("User", authorId));

        assertThat(entities.getFirst())
                .as("o __typename da resposta é reposto a partir do @Name da classe que voltou")
                .containsEntry("__typename", "Author")
                .containsEntry("email", Realm.AUTHOR_USERNAME);
    }

    @Test
    void theCostOfTheCallDoesNotGrowWithTheNumberOfRepresentations() {
        GraphQl author = asAuthor();
        List<String> ids = List.of(
                author.createPost("Post 1", "c"), author.createPost("Post 2", "c"),
                author.createPost("Post 3", "c"), author.createPost("Post 4", "c"),
                author.createPost("Post 5", "c"));

        long forOne = statementsResolving(ids.subList(0, 1));
        long forFive = statementsResolving(ids);

        assertThat(forOne).isPositive();
        // sem batch-resolving-enabled seriam cinco consultas — o N+1 atravessando o roteador
        assertThat(forFive)
                .as("5 representações custaram %d statements contra %d de 1", forFive, forOne)
                .isEqualTo(forOne);
    }

    @SuppressWarnings("unchecked")
    private long statementsResolving(List<String> postIds) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        List<Map<String, Object>> entities = (List<Map<String, Object>>) anonymous
                .execute(ENTITIES, "reps", postIds.stream().map(id -> ref("Post", id)).toList())
                .list("_entities");
        assertThat(entities).hasSize(postIds.size());
        return statistics.getPrepareStatementCount();
    }
}
