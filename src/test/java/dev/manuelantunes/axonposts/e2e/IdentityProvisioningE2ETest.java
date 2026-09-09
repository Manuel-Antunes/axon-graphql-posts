package dev.manuelantunes.axonposts.e2e;

import dev.manuelantunes.axonposts.application.user.command.LinkAccountCommand.LinkAccount;
import dev.manuelantunes.axonposts.application.user.command.PromoteToAuthorCommand.PromoteToAuthor;
import dev.manuelantunes.axonposts.application.user.command.RegisterUserCommand.RegisterUser;
import dev.manuelantunes.axonposts.domain.user.AuthProvider;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.support.AbstractGraphQlE2ETest;
import dev.manuelantunes.axonposts.support.KeycloakContainerConfig;
import org.junit.jupiter.api.Test;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A costura entre o Keycloak e o banco local: provisionamento just-in-time e account linking.
 *
 * <h2>O que só existe integrado</h2>
 * Cada afirmação aqui depende de um token assinado pelo realm de verdade:
 * <ul>
 *   <li>o {@code issuer-uri} descobrir o JWKS e o decoder validar a assinatura;</li>
 *   <li>as roles saírem de {@code realm_access.roles} — um erro de prefixo no conversor faria todo
 *       {@code hasRole} falhar em silêncio, e um token forjado não pegaria isso;</li>
 *   <li>o {@code sub} do Keycloak virar linha em {@code accounts}, ligada a um {@code User} local com id
 *       próprio;</li>
 *   <li>a role {@code author} virar linha em {@code authors}.</li>
 * </ul>
 */
class IdentityProvisioningE2ETest extends AbstractGraphQlE2ETest {

    @Autowired
    private UserRepository users;

    @Autowired
    private Clock clock;

    @Autowired
    private CommandGateway commandGateway;

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
        assertThat(users.findByEmail(Email.of(KeycloakContainerConfig.AUTHOR_USERNAME))).isEmpty();

        asAuthor().document(
                //language=GraphQL
                "{ me { __typename id name email } }")
                .execute()
                .path("me.__typename").entity(String.class).isEqualTo("Author")
                .path("me.name").entity(String.class).isEqualTo("Manuel Antunes")
                .path("me.email").entity(String.class).isEqualTo(KeycloakContainerConfig.AUTHOR_USERNAME);

        assertThat(users.findByEmail(Email.of(KeycloakContainerConfig.AUTHOR_USERNAME)))
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
        HttpGraphQlTester author = asAuthor();

        String first = author.document(
                //language=GraphQL
                "{ me { id } }").execute().path("me.id").entity(String.class).get();
        String second = author.document(
                //language=GraphQL
                "{ me { id } }").execute().path("me.id").entity(String.class).get();

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from accounts", Integer.class)).isEqualTo(1);
    }

    @Test
    void theAccountIsVisibleAndHasNoLocalPassword() {
        asAuthor().document(
                //language=GraphQL
                "{ me { accounts { provider subject hasPassword } } }")
                .execute()
                .path("me.accounts").entityList(Object.class).hasSize(1)
                .path("me.accounts[0].provider").entity(String.class).isEqualTo("KEYCLOAK")
                // o "algumas contas têm senha e outras não", ponta a ponta: esta não tem
                .path("me.accounts[0].hasPassword").entity(Boolean.class).isEqualTo(false);
    }

    @Test
    void anExistingLocalUserIsLinkedInsteadOfDuplicated() {
        // já existe alguém local com este e-mail, sem conta nenhuma ligada
        UserId existingId = registerLocally(KeycloakContainerConfig.READER_USERNAME, "Cadastro Antigo", false);

        asReader().document(
                //language=GraphQL
                "{ me { id } }").execute()
                .path("me.id").entity(String.class).isEqualTo(existingId.value());

        // account linking: a mesma pessoa, uma conta a mais — e não um segundo usuário
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
        assertThat(users.findById(existingId))
                .hasValueSatisfying(user -> assertThat(user.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue());
    }

    @Test
    void linkingASecondProviderKeepsTheSameUserAndItsPosts() {
        HttpGraphQlTester author = asAuthor();
        String userId = author.document(
                //language=GraphQL
                "{ me { id } }").execute().path("me.id").entity(String.class).get();
        createPost(author, "Escrito antes do segundo provedor", "conteúdo");

        // a mesma pessoa passa a entrar também pelo Google: uma credencial a mais no MESMO agregado
        commandGateway.sendAndWait(new LinkAccount(UserId.of(userId), AuthProvider.GOOGLE, "google-sub-1"));

        author.document(
                //language=GraphQL
                """
                        { me { id accounts { provider }
                               ... on Author { posts(first: 5) { edges { node { title } } } } } }""")
                .execute()
                .path("me.id").entity(String.class).isEqualTo(userId)
                .path("me.accounts").entityList(Object.class).hasSize(2)
                .path("me.posts.edges").entityList(Object.class).hasSize(1)
                .path("me.posts.edges[0].node.title").entity(String.class)
                .isEqualTo("Escrito antes do segundo provedor");

        // um usuário só: ligar provedor é inserir linha em accounts, não criar gente nova
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
    }

    /**
     * A promoção depois que {@code User} virou entidade polimórfica do Axon.
     *
     * <h3>O que mudou, e por quê</h3>
     * Antes a promoção era um INSERT na tabela filha e o usuário mantinha o id. Agora o tipo concreto sai
     * do primeiro evento do stream e <b>não muda em runtime</b> — a documentação do Axon é explícita. Um
     * leitor promovido ganha um agregado novo, e o antigo é encerrado.
     * <p>
     * O que este teste garante é que a troca de identidade não perde nada: e-mail, nome e credenciais
     * atravessam, os dois streams ficam ligados nos dois sentidos, e o leitor encerrado some das consultas
     * sem sumir do banco.
     */
    @Test
    void aLocalReaderIsPromotedIntoANewAggregate() {
        UserId readerId = registerLocally(KeycloakContainerConfig.PROMOTED_USERNAME, "Autor Recente", false);
        assertThat(users.findById(readerId).orElseThrow().isAuthor()).isFalse();

        String authorId = as(KeycloakContainerConfig.PROMOTED_USERNAME)
                .document(
                        //language=GraphQL
                        "{ me { __typename id } }")
                .execute()
                .path("me.__typename").entity(String.class).isEqualTo("Author")
                .path("me.id").entity(String.class).get();

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
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(2);
        // mas só o autor responde às consultas por e-mail
        assertThat(users.findByEmail(Email.of(KeycloakContainerConfig.PROMOTED_USERNAME)))
                .hasValueSatisfying(user -> assertThat(user.id().value()).isEqualTo(authorId));
    }

    @Test
    void aPromotedUserCanWriteImmediately() {
        registerLocally(KeycloakContainerConfig.PROMOTED_USERNAME, "Autor Recente", false);

        // a promoção precisa valer já nesta requisição: o CurrentUser relê para pegar o tipo novo
        createPost(as(KeycloakContainerConfig.PROMOTED_USERNAME), "Primeiro depois da promoção", "c");

        anonymous.document(
                //language=GraphQL
                "{ posts(first: 5) { edges { node { title author { name } } } } }")
                .execute()
                .path("posts.edges").entityList(Object.class).hasSize(1);
    }

    /**
     * Um token malformado nem chega ao GraphQL: o filtro do resource server recusa a requisição em HTTP,
     * com {@code 401} e {@code WWW-Authenticate}, antes de existir um documento a executar.
     * <p>
     * É por isso que a asserção é sobre o status e não sobre um erro GraphQL — e é uma diferença que vale
     * conhecer: um token <b>ausente</b> segue anônimo e esbarra no {@code @PreAuthorize} (erro GraphQL),
     * um token <b>inválido</b> morre antes (erro HTTP).
     */
    /**
     * Auto-exclusão e reativação: a conta some das consultas e volta no login seguinte.
     * <p>
     * Sem a reativação, {@code findByAccount} não acharia nada (o {@code @SQLRestriction} esconde os
     * apagados) e a entrada seguinte criaria um <b>segundo</b> usuário, deixando os posts do primeiro
     * órfãos e invisíveis.
     */
    @Test
    void deletingTheAccountHidesItAndLoggingInAgainBringsItBack() {
        HttpGraphQlTester author = asAuthor();
        String userId = author.document(
                //language=GraphQL
                "{ me { id } }").execute().path("me.id").entity(String.class).get();
        createPost(author, "Escrito antes de apagar a conta", "conteúdo");

        author.document(
                //language=GraphQL
                "mutation { deleteMe }").execute()
                .path("deleteMe").entity(Boolean.class).isEqualTo(true);

        // some das consultas, mas a linha continua marcada
        assertThat(users.findById(UserId.of(userId))).isEmpty();
        assertThat(jdbc.queryForObject(
                "select count(*) from users where id = ? and deleted_at is not null", Integer.class, userId))
                .isEqualTo(1);

        // enquanto apagado, os posts dele também somem — ver deletingTheAccountAlsoHidesTheAuthorsPosts
        anonymous.document(
                //language=GraphQL
                "{ posts(first: 5) { edges { node { title } } } }").execute()
                .path("posts.edges").entityList(Object.class).hasSize(0);

        // entrar de novo reativa: mesmo id, mesmos posts, e nenhum usuário a mais
        asAuthor().document(
                //language=GraphQL
                "{ me { id ... on Author { posts(first: 5) { edges { node { title } } } } } }")
                .execute()
                .path("me.id").entity(String.class).isEqualTo(userId)
                .path("me.posts.edges").entityList(Object.class).hasSize(1);

        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
    }

    /**
     * O efeito colateral que ninguém programou explicitamente, e que por isso precisa de teste.
     * <p>
     * Apagar a conta esconde os posts do autor. Nada os toca — eles não têm {@code deleted_at} próprio —,
     * mas {@code Post.author} é {@code @ManyToOne(optional = false)} e o {@code @SQLRestriction} do
     * {@code User} torna o join <b>INNER</b> contra uma linha filtrada. O post simplesmente não passa.
     * <p>
     * É o tipo de comportamento que emerge do mapeamento e que só um teste de integração enxerga: contra
     * um duplo em memória, os posts continuariam aparecendo. Fixá-lo aqui é o que impede a semântica de
     * mudar sem ninguém perceber no dia que a associação virar opcional.
     */
    @Test
    void deletingTheAccountAlsoHidesTheAuthorsPosts() {
        HttpGraphQlTester author = asAuthor();
        createPost(author, "Some com o autor", "conteúdo");
        anonymous.document(
                //language=GraphQL
                "{ posts(first: 5) { edges { node { title } } } }").execute()
                .path("posts.edges").entityList(Object.class).hasSize(1);

        author.document(
                //language=GraphQL
                "mutation { deleteMe }").execute();

        assertThat(jdbc.queryForObject("select count(*) from posts", Integer.class))
                .as("a linha do post continua no banco: quem sumiu foi o autor").isEqualTo(1);
        anonymous.document(
                //language=GraphQL
                "{ posts(first: 5) { edges { node { title } } } }").execute()
                .path("posts.edges").entityList(Object.class).hasSize(0);

        // e reativar traz tudo de volta, sem nada ter sido reescrito
        asAuthor().document(
                //language=GraphQL
                "{ me { id } }").execute();
        anonymous.document(
                //language=GraphQL
                "{ posts(first: 5) { edges { node { title } } } }").execute()
                .path("posts.edges").entityList(Object.class).hasSize(1);
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
        UserId readerId = registerLocally(KeycloakContainerConfig.PROMOTED_USERNAME, "Autor Recente", false);
        UserId successorId = UserId.newId();

        // só o primeiro passo: o agregado do leitor é encerrado e nada mais acontece
        commandGateway.sendAndWait(new PromoteToAuthor(readerId, successorId));

        assertThat(users.findById(readerId).orElseThrow().isSuperseded()).isTrue();
        assertThat(users.findById(successorId)).as("o sucessor não deveria existir ainda").isEmpty();

        // o login seguinte conclui o que faltou
        as(KeycloakContainerConfig.PROMOTED_USERNAME)
                .document(
                        //language=GraphQL
                        "{ me { __typename id } }")
                .execute()
                .path("me.__typename").entity(String.class).isEqualTo("Author")
                // no MESMO id que já estava gravado em supersededBy: o destino era determinístico
                .path("me.id").entity(String.class).isEqualTo(successorId.value());

        assertThat(users.findById(successorId)).hasValueSatisfying(author -> {
            assertThat(author.isAuthor()).isTrue();
            assertThat(author.supersedes()).isEqualTo(readerId);
            assertThat(author.isLinkedTo(AuthProvider.KEYCLOAK)).isTrue();
        });

        // e não sobrou um terceiro usuário no caminho
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(2);
    }

    @Test
    void anInvalidTokenIsRefusedAtTheHttpLayer() {
        webTestClient.post()
                .uri("/graphql")
                .header("Authorization", "Bearer nao.e.um.jwt")
                .header("Content-Type", "application/json")
                .bodyValue(
                        // JSON, e não GraphQL: aqui a query vai dentro do corpo, escapada
                        //language=JSON
                        "{\"query\":\"{ me { id } }\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
