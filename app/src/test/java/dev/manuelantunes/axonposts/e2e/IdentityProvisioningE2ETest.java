package dev.manuelantunes.axonposts.e2e;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.application.user.command.LinkAccountCommand.LinkAccount;
import dev.manuelantunes.axonposts.application.user.command.PromoteToAuthorCommand.PromoteToAuthor;
import dev.manuelantunes.axonposts.application.user.command.RegisterUserCommand.RegisterUser;
import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import io.quarkus.test.junit.QuarkusTest;
import dev.manuelantunes.axonposts.support.GraphQl;
import dev.manuelantunes.axonposts.support.Realm;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A costura entre o Keycloak e o banco local: provisionamento just-in-time e account linking.
 *
 * <h2>O que só existe integrado</h2>
 * Cada afirmação aqui depende de um token assinado pelo realm de verdade:
 * <ul>
 *   <li>o {@code quarkus.oidc.auth-server-url} descobrir o JWKS e validar a assinatura;</li>
 *   <li>as roles saírem de {@code realm_access.roles} — se o Quarkus deixasse de lê-las, todo
 *       {@code @RolesAllowed} falharia em silêncio, e um token forjado não pegaria isso;</li>
 *   <li>o {@code sub} do Keycloak virar linha em {@code accounts}, ligada a um {@code User} local com id
 *       próprio;</li>
 *   <li>a role {@code author} virar linha em {@code authors}.</li>
 * </ul>
 */
@QuarkusTest
class IdentityProvisioningE2ETest extends AbstractGraphQlE2ETest {

    @Inject
    UserRepository users;

    @Inject
    CommandGateway commandGateway;

    /**
     * Cria um usuário local pelo <b>command</b>, e não por {@code users.save(...)}.
     * <p>
     * Desde que {@code User} virou agregado event-sourced, salvar direto no repositório escreveria a linha
     * sem escrever o stream — e o próximo command sobre esse usuário não acharia agregado nenhum para
     * reidratar. O read model deixou de ser um lugar onde se inventa estado.
     */
    private UserId registerLocally(String email, String name, boolean author) {
        UserId id = UserId.newId();
        commandGateway.sendAndWait(new RegisterUser(id, email, name, author, null, null));
        return id;
    }

    @Test
    void theFirstRequestWithATokenCreatesTheLocalUser() {
        assertThat(users.findByEmail(Email.of(Realm.AUTHOR_USERNAME))).isEmpty();

        var response = asAuthor().execute("{ me { __typename id name email } }");

        assertThat(response.string("me.__typename")).isEqualTo("Author");
        assertThat(response.string("me.name")).isEqualTo("Manuel Antunes");
        assertThat(response.string("me.email")).isEqualTo(Realm.AUTHOR_USERNAME);

        assertThat(users.findByEmail(Email.of(Realm.AUTHOR_USERNAME)))
                .hasValueSatisfying(user -> {
                    assertThat(user.isAuthor()).isTrue();
                    assertThat(user.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue();
                    // dois espaços de identidade: o sub do Keycloak NÃO é a chave primária local
                    assertThat(user.accountFor(AuthProvider.KEYCLOAK).orElseThrow().subject())
                            .isNotEqualTo(user.id().value());
                });
    }

    @Test
    void provisioningIsIdempotent() {
        GraphQl author = asAuthor();

        String first = author.execute("{ me { id } }").string("me.id");
        String second = author.execute("{ me { id } }").string("me.id");

        assertThat(second).isEqualTo(first);
        assertThat(count("select count(*) from users")).isEqualTo(1);
        assertThat(count("select count(*) from accounts")).isEqualTo(1);
    }

    @Test
    void theAccountIsVisibleAndHasNoLocalPassword() {
        var response = asAuthor().execute("{ me { accounts { provider subject hasPassword } } }");

        assertThat(response.list("me.accounts")).hasSize(1);
        assertThat(response.string("me.accounts[0].provider")).isEqualTo("KEYCLOAK");
        // o "algumas contas têm senha e outras não", ponta a ponta: esta não tem
        assertThat(response.bool("me.accounts[0].hasPassword")).isFalse();
    }

    @Test
    void anExistingLocalUserIsLinkedInsteadOfDuplicated() {
        // já existe alguém local com este e-mail, sem conta nenhuma ligada
        UserId existingId = registerLocally(Realm.READER_USERNAME, "Cadastro Antigo", false);

        assertThat(asReader().execute("{ me { id } }").string("me.id")).isEqualTo(existingId.value());

        // account linking: a mesma pessoa, uma conta a mais — e não um segundo usuário
        assertThat(count("select count(*) from users")).isEqualTo(1);
        assertThat(users.findById(existingId))
                .hasValueSatisfying(user -> assertThat(user.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue());
    }

    @Test
    void linkingASecondProviderKeepsTheSameUserAndItsPosts() {
        GraphQl author = asAuthor();
        String userId = author.execute("{ me { id } }").string("me.id");
        author.createPost("Escrito antes do segundo provedor", "conteúdo");

        // a mesma pessoa passa a entrar também pelo Google: uma credencial a mais no MESMO agregado
        commandGateway.sendAndWait(new LinkAccount(UserId.of(userId), AuthProvider.GOOGLE, "google-sub-1"));

        var response = author.execute("""
                { me { id accounts { provider }
                       ... on Author { posts(first: 5) { edges { node { title } } } } } }""");

        assertThat(response.string("me.id")).isEqualTo(userId);
        assertThat(response.list("me.accounts")).hasSize(2);
        assertThat(response.list("me.posts.edges")).hasSize(1);
        assertThat(response.string("me.posts.edges[0].node.title"))
                .isEqualTo("Escrito antes do segundo provedor");

        // um usuário só: ligar provedor é inserir linha em accounts, não criar gente nova
        assertThat(count("select count(*) from users")).isEqualTo(1);
    }

    /**
     * A promoção depois que {@code User} virou entidade polimórfica do Axon.
     *
     * <h3>O que ela significa</h3>
     * O tipo concreto sai do primeiro evento do stream e <b>não muda em runtime</b> — a documentação do
     * Axon é explícita. Um leitor promovido ganha um agregado novo, e o antigo é encerrado.
     * <p>
     * O que este teste garante é que a troca de identidade não perde nada: e-mail, nome e credenciais
     * atravessam, os dois streams ficam ligados nos dois sentidos, e o leitor encerrado some das consultas
     * sem sumir do banco.
     */
    @Test
    void aLocalReaderIsPromotedIntoANewAggregate() {
        UserId readerId = registerLocally(Realm.PROMOTED_USERNAME, "Autor Recente", false);
        assertThat(users.findById(readerId).orElseThrow().isAuthor()).isFalse();

        var response = as(Realm.PROMOTED_USERNAME).execute("{ me { __typename id } }");
        assertThat(response.string("me.__typename")).isEqualTo("Author");
        String authorId = response.string("me.id");

        // id NOVO: o agregado do leitor não virou autor, foi substituído por um
        assertThat(authorId).isNotEqualTo(readerId.value());

        assertThat(users.findById(UserId.of(authorId))).hasValueSatisfying(author -> {
            assertThat(author.isAuthor()).isTrue();
            assertThat(author.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue();
            // o caminho de volta: dá para saber quem ele era
            assertThat(author.supersedes()).isEqualTo(readerId);
        });

        assertThat(users.findById(readerId)).hasValueSatisfying(reader -> {
            assertThat(reader.isSuperseded()).isTrue();
            assertThat(reader.supersededBy()).isEqualTo(UserId.of(authorId));
            // e ele soltou as credenciais: um usuário encerrado não tem por onde entrar
            assertThat(reader.accounts()).isEmpty();
        });

        // as duas linhas continuam no banco — o histórico não é apagado, só encerrado
        assertThat(count("select count(*) from users")).isEqualTo(2);
        // mas só o autor responde às consultas por e-mail
        assertThat(users.findByEmail(Email.of(Realm.PROMOTED_USERNAME)))
                .hasValueSatisfying(user -> assertThat(user.id().value()).isEqualTo(authorId));
    }

    @Test
    void aPromotedUserCanWriteImmediately() {
        registerLocally(Realm.PROMOTED_USERNAME, "Autor Recente", false);

        // a promoção precisa valer já nesta requisição: o CurrentUser relê para pegar o tipo novo
        as(Realm.PROMOTED_USERNAME).createPost("Primeiro depois da promoção", "c");

        assertThat(anonymous.execute("{ posts(first: 5) { edges { node { title author { name } } } } }")
                .list("posts.edges")).hasSize(1);
    }

    /**
     * Auto-exclusão e reativação: a conta some das consultas e volta no login seguinte.
     * <p>
     * Sem a reativação, {@code findByAccount} não acharia nada (o {@code @SQLRestriction} esconde os
     * apagados) e a entrada seguinte criaria um <b>segundo</b> usuário, deixando os posts do primeiro
     * órfãos e invisíveis.
     */
    @Test
    void deletingTheAccountHidesItAndLoggingInAgainBringsItBack() {
        GraphQl author = asAuthor();
        String userId = author.execute("{ me { id } }").string("me.id");
        author.createPost("Escrito antes de apagar a conta", "conteúdo");

        assertThat(author.execute("mutation { deleteMe }").bool("deleteMe")).isTrue();

        // some das consultas, mas a linha continua marcada
        assertThat(users.findById(UserId.of(userId))).isEmpty();
        assertThat(count("select count(*) from users where id = ? and deleted_at is not null", userId))
                .isEqualTo(1);

        // enquanto apagado, os posts dele também somem — ver deletingTheAccountAlsoHidesTheAuthorsPosts
        assertThat(anonymous.execute("{ posts(first: 5) { edges { node { title } } } }")
                .list("posts.edges")).isEmpty();

        // entrar de novo reativa: mesmo id, mesmos posts, e nenhum usuário a mais
        var back = asAuthor().execute(
                "{ me { id ... on Author { posts(first: 5) { edges { node { title } } } } } }");
        assertThat(back.string("me.id")).isEqualTo(userId);
        assertThat(back.list("me.posts.edges")).hasSize(1);

        assertThat(count("select count(*) from users")).isEqualTo(1);
    }

    /**
     * O efeito colateral que ninguém programou explicitamente, e que por isso precisa de teste.
     * <p>
     * Apagar a conta esconde os posts do autor. Nada os toca — eles não têm {@code deleted_at} próprio —,
     * mas {@code Post.author} é {@code @ManyToOne(optional = false)} e o {@code @SQLRestriction} do
     * {@code User} torna o join <b>INNER</b> contra uma linha filtrada. O post simplesmente não passa.
     * <p>
     * É o tipo de comportamento que emerge do mapeamento e que só um teste de integração enxerga: contra
     * um duplo em memória, os posts continuariam aparecendo.
     */
    @Test
    void deletingTheAccountAlsoHidesTheAuthorsPosts() {
        GraphQl author = asAuthor();
        author.createPost("Some com o autor", "conteúdo");
        assertThat(anonymous.execute("{ posts(first: 5) { edges { node { title } } } }")
                .list("posts.edges")).hasSize(1);

        author.execute("mutation { deleteMe }");

        assertThat(count("select count(*) from posts"))
                .as("a linha do post continua no banco: quem sumiu foi o autor").isEqualTo(1);
        assertThat(anonymous.execute("{ posts(first: 5) { edges { node { title } } } }")
                .list("posts.edges")).isEmpty();

        // e reativar traz tudo de volta, sem nada ter sido reescrito
        asAuthor().execute("{ me { id } }");
        assertThat(anonymous.execute("{ posts(first: 5) { edges { node { title } } } }")
                .list("posts.edges")).hasSize(1);
    }

    /**
     * A promoção interrompida, e a recuperação para a frente.
     *
     * <h3>Como a falha é provocada</h3>
     * Despachando <b>só</b> o primeiro passo da sequência ({@code PromoteToAuthor}) e nenhum dos
     * seguintes. É exatamente o estado que sobraria se o {@code RegisterUser} tivesse falhado: um leitor
     * encerrado, sem credenciais, e um sucessor que nunca existiu.
     *
     * <h3>O que o teste garante</h3>
     * Que a pessoa não fica sem conta. No login seguinte o {@code UserProvisioning} detecta o encerrado
     * órfão, conclui a sequência no <b>mesmo</b> id de sucessor que já estava gravado, e devolve um autor
     * funcional — sem job, sem agendador, sem saga.
     */
    @Test
    void anInterruptedPromotionIsFinishedOnTheNextLogin() {
        UserId readerId = registerLocally(Realm.PROMOTED_USERNAME, "Autor Recente", false);
        UserId successorId = UserId.newId();

        // só o primeiro passo: o agregado do leitor é encerrado e nada mais acontece
        commandGateway.sendAndWait(new PromoteToAuthor(readerId, successorId));

        assertThat(users.findById(readerId).orElseThrow().isSuperseded()).isTrue();
        assertThat(users.findById(successorId)).as("o sucessor não deveria existir ainda").isEmpty();

        // o login seguinte conclui o que faltou
        var response = as(Realm.PROMOTED_USERNAME).execute("{ me { __typename id } }");
        assertThat(response.string("me.__typename")).isEqualTo("Author");
        // no MESMO id que já estava gravado em supersededBy: o destino era determinístico
        assertThat(response.string("me.id")).isEqualTo(successorId.value());

        assertThat(users.findById(successorId)).hasValueSatisfying(author -> {
            assertThat(author.isAuthor()).isTrue();
            assertThat(author.supersedes()).isEqualTo(readerId);
            assertThat(author.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue();
        });

        // e não sobrou um terceiro usuário no caminho
        assertThat(count("select count(*) from users")).isEqualTo(2);
    }

    /**
     * Um token malformado nem chega ao GraphQL: o {@code quarkus-oidc} recusa a requisição em HTTP, com
     * {@code 401}, antes de existir um documento a executar.
     * <p>
     * É por isso que a asserção é sobre o status e não sobre um erro GraphQL — e é uma diferença que vale
     * conhecer: um token <b>ausente</b> segue anônimo e esbarra no {@code @Authenticated} (erro GraphQL
     * com {@code code: unauthorized}), um token <b>inválido</b> morre antes (erro HTTP).
     */
    @Test
    void anInvalidTokenIsRefusedAtTheHttpLayer() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer nao.e.um.jwt")
                .body("{\"query\":\"{ me { id } }\"}")
                .post("/graphql")
                .then().statusCode(401);
    }
}
