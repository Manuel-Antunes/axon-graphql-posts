package dev.manuelantunes.axonposts.support;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.BeforeEach;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;

/**
 * Base dos testes ponta a ponta: aplicação de verdade, Postgres de verdade, Keycloak de verdade, token
 * de verdade.
 *
 * <h2>O que "ponta a ponta" significa aqui</h2>
 * A requisição entra por HTTP com um {@code Authorization: Bearer} emitido pelo realm, atravessa o
 * {@code quarkus-oidc}, o {@code @RolesAllowed}, o provisionamento, o command bus do Axon, o domínio, o
 * JPA e a projeção, e volta como JSON do GraphQL. Nada é dublado. É a diferença para os testes de
 * command, que montam só o handler e um repositório em memória.
 *
 * <h2>Uma aplicação para todas as subclasses</h2>
 * {@code @QuarkusTest} sobe a aplicação <b>uma vez</b> por perfil de teste e a compartilha com todas as
 * classes anotadas. É a mesma economia que o Spring faz reaproveitando o contexto — com a diferença de
 * que aqui os containers (Postgres e Keycloak) também vêm do Dev Services, sem uma linha de
 * Testcontainers escrita à mão. As três classes de infraestrutura do projeto Spring
 * ({@code Containers}, {@code KeycloakContainerConfig}, {@code KeycloakTokens}) viraram
 * {@link Realm} e {@link GraphQl}.
 * <p>
 * Uma subclasse que acrescente {@code @TestProfile} ganha uma aplicação própria e a suíte paga outra
 * subida — é o equivalente exato do {@code @TestPropertySource} no Spring.
 *
 * <h2>Por que o {@code @QuarkusTest} fica nas subclasses, e não aqui</h2>
 * Porque é a anotação que faz o Quarkus registrar a classe de teste como <b>bean</b>, em tempo de build,
 * para que os {@code @Inject} dela sejam satisfeitos. O registro olha para a classe anotada, não para a
 * hierarquia: com a anotação só nesta base, a extensão do JUnit ativa (ela é herdada) mas cada subclasse
 * falha com "No bean found for required type". Anotar cada concreta é uma linha, e é onde o Quarkus
 * espera encontrá-la.
 */
public abstract class AbstractGraphQlE2ETest {

    @Inject
    protected DataSource dataSource;

    @Inject
    protected EntityManagerFactory entityManagerFactory;

    protected GraphQl anonymous;

    /**
     * Cada teste começa com o banco vazio.
     * <p>
     * As classes compartilham aplicação e banco — subir um por método custaria dezenas de segundos —, e o
     * preço disso é que a ordem de execução vazaria de um teste para o outro. Um que afirme "este usuário
     * ainda não existe" passa sozinho e falha depois de outro tê-lo provisionado.
     * <p>
     * O {@code CASCADE} resolve as chaves estrangeiras entre posts, contas e usuários sem precisar acertar
     * a ordem do truncate à mão.
     * <p>
     * <h2>O event store entra no truncate, e isso mudou</h2>
     * Enquanto ele era em memória, ficar de fora era inofensivo: todo id é um UUID novo e nenhum teste
     * reidratava o stream de outro. Persistente, ficar de fora cria um estado <b>incoerente</b> — a linha
     * da tag é apagada e o stream do agregado Tag continua lá. O efeito é um {@code CreateTag} que falha
     * com {@code TagAlreadyExistsException} contra um agregado que existe no store e não existe no read
     * model, e nada o reconstrói.
     * <p>
     * Limpar os dois juntos é o que mantém store e projeção dizendo a mesma coisa em cada método.
     * {@code tokenentry} entra pelo mesmo motivo, embora nenhum processor daqui use token: deixar
     * posição de leitura de uma execução anterior seria semear a próxima.
     *
     * <h2>UM truncate, e isso importa</h2>
     * Eram dois comandos — read model e tabelas do Axon —, e dois comandos são duas transações. Trabalho
     * em voo de um teste anterior podia se interleavar entre elas e deixar estado <b>incoerente</b>:
     * agregado no store sem a linha correspondente. O sintoma aparecia longe da causa, num
     * {@code createPost} de outro teste:
     * <pre>
     * Tag já existe: fa65e148-...      (o agregado sobreviveu ao primeiro truncate)
     * Tag não encontrada: fa65e148-... (a linha não sobreviveu)
     * </pre>
     * Num único {@code truncate} as duas metades caem juntas, atomicamente. Só passou a ser visível
     * quando a identidade da tag padrão virou derivada: antes cada tentativa sorteava um id novo, então
     * nenhuma colidia com o que tinha sobrado.
     */
    @BeforeEach
    void beforeEach() {
        resetDatabase();
        anonymous = GraphQl.anonymous();
    }

    /**
     * Zera o banco e restaura a semente. {@code protected} porque um teste que precise limpar NO MEIO
     * dele — o {@code BatchLoadingE2ETest} faz isso para medir statements de um estado conhecido — tem
     * de limpar do mesmo jeito. Um {@code truncate} solto ali apagaria a tag padrão e o próximo
     * {@code createPost} falharia com {@code Tag não encontrada}, longe da causa.
     */
    protected void resetDatabase() {
        execute("truncate table post_tags, posts, tags, accounts, authors, users, "
                + "aggregateevententry, tokenentry, axon_message_inbox cascade");
        /*
         * A tag padrão é SEMENTE (migration V5), não dado de teste: o truncate a apaga junto, então
         * restaurar o banco inclui restaurá-la. Sem isto, o primeiro `CompletePost` de cada método falha
         * com `Tag não encontrada` — e o erro não diz que o que falta é dado de referência.
         */
        execute("insert into tags (id, name, created_at) values "
                + "('fa65e148-3f7c-3860-a765-a70f54983048', 'Untagged', '2026-01-01T00:00:00Z') "
                + "on conflict do nothing");
    }

    protected GraphQl asAuthor() {
        return GraphQl.asAuthor();
    }

    protected GraphQl asReader() {
        return GraphQl.asReader();
    }

    protected GraphQl as(String username) {
        return GraphQl.as(username);
    }

    /** O {@code jdbc.queryForObject(..., Integer.class)} do projeto Spring, em JDBC puro. */
    protected int count(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("falha ao consultar: " + sql, e);
        }
    }

    protected void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("falha ao executar: " + sql, e);
        }
    }

    /**
     * Cria um post e <b>espera</b> a tag padrão chegar.
     *
     * <h3>Por que a espera existe, e por que ela é rápida em teste</h3>
     * Em produção a primeira tag é decidida por OUTRO serviço: o post nasce pré-criado na versão 1, o
     * {@code PostPreCreatedEvent} atravessa o broker, e a versão 2 chega depois. Em teste esse passo é
     * dublado dentro do processo ({@code InProcessTagAssignment}), e como o Axon espera o futuro do
     * {@code onAfterCommit}, o {@code createPost} já volta na versão 2 — a espera aqui costuma
     * satisfazer na primeira tentativa.
     * <p>
     * Ela fica de qualquer forma, e concentrada aqui em vez de espalhada em {@code await()} pelos
     * testes: é o que mantém legível a distinção entre os testes que afirmam <i>o que a mutation
     * devolve</i> e os que dependem do <i>estado final</i> — e é o que sobreviveria a alguém ligar o
     * caminho real.
     */
    protected String createTaggedPost(GraphQl author, String title, String content) {
        String id = author.createPost(title, content);
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(
                anonymous.execute("query Etiquetado($id: ID!) { post(id: $id) { version } }", "id", id)
                        .integer("post.version"))
                .as("a primeira tag chega e leva o post à versão 2 (completo)")
                .isEqualTo(2));
        return id;
    }

    /**
     * Intervalo entre duas amostras da estatística. Duas amostras <b>iguais</b> seguidas definem o
     * silêncio — uma só não bastaria, porque um processor em lote pode passar 150 ms entre uma página e
     * a seguinte.
     */
    private static final Duration QUIET_SAMPLE = Duration.ofMillis(150);

    /**
     * Zera a estatística do Hibernate <b>depois</b> de o banco ficar quieto, e a devolve para a medição.
     *
     * <h2>Por que esperar, e por que isso não é frescura de teste</h2>
     * A {@link Statistics} é da {@code SessionFactory} — ela conta os {@code PreparedStatement} de
     * <b>todas</b> as threads, e esta aplicação tem uma que não é a do teste: o processor que avisa os
     * assinantes ({@code application.post.event}) é <i>pooled streaming</i>, roda no próprio pool e lê
     * a linha de cada post que passa pelo event store.
     * <p>
     * Sem esperar, a PRIMEIRA medição de um teste cai em cima dessa varredura — os posts acabaram de ser
     * criados — e a segunda não. O resultado é uma comparação entre uma medição poluída e uma limpa, e
     * ela falha com números que não têm nada a ver com o lote: medido, 11 statements para 1 post contra
     * 4 para 5 posts, e 8 para uma representação contra 1 para cinco. O teste acusaria um N+1 onde não
     * há, e — pior — passaria a esconder um N+1 de verdade por baixo do ruído.
     * <p>
     * É o mesmo efeito que este projeto já tinha registrado quando um pacote caiu num processor pooled
     * por engano. A diferença é que agora ele é <b>de propósito</b>: sem um processor streaming a
     * subscription não funciona com mais de um container. Então quem tem de se adaptar é a medição.
     *
     * <h2>O que "quieto" quer dizer</h2>
     * Duas amostras consecutivas da mesma contagem, 150 ms entre elas. Não é uma pausa
     * fixa: um banco que continua trabalhando adia a medição em vez de deixá-la começar errada, e um
     * banco já parado custa duas amostras. O teto existe só para a falha ser um erro de espera, e não um
     * teste pendurado.
     */
    protected Statistics statisticsOfAQuietDatabase() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        AtomicLong previous = new AtomicLong(-1);
        await().atMost(Duration.ofSeconds(20))
                .pollDelay(QUIET_SAMPLE)
                .pollInterval(QUIET_SAMPLE)
                .until(() -> {
                    long now = statistics.getPrepareStatementCount();
                    return previous.getAndSet(now) == now;
                });

        statistics.clear();
        return statistics;
    }

    /**
     * O CUSTO em statements de uma consulta, medido pelo MENOR de várias execuções.
     *
     * <h2>Por que o menor, e por que isto não é um truque</h2>
     * {@link #statisticsOfAQuietDatabase()} prova que o banco estava quieto ANTES da janela — e não
     * que ele fica quieto DURANTE. O processor que notifica os assinantes é assíncrono e conta na
     * MESMA {@code Statistics} (ela é da {@code SessionFactory}, não da sessão), então ele pode
     * acordar no meio da medição e somar statements que não são da consulta.
     * <p>
     * A observação que resolve: o processor só ACRESCENTA. A consulta custa sempre o mesmo, e
     * interferência só faz a conta subir — então <b>o menor de várias execuções É o custo real</b>.
     * <p>
     * E a propriedade que o teste afirma continua intacta: um N+1 de verdade encarece TODAS as
     * execuções, inclusive a mais barata. O mínimo sobe junto e a asserção quebra — que é o ponto.
     *
     * <h2>O que ele substituiu</h2>
     * A medição de uma amostra só, que violava o <b>R</b> de REPEATABLE do FIRST: o mesmo código
     * passava 3 de 3 com a classe isolada e falhava com as oito classes ponta a ponta juntas
     * (`dois autores custaram 7 statements contra 4 de um autor`), porque com mais posts no event
     * store o processor varre mais e a chance de cair em cima da janela cresce. Um teste que depende
     * de quem mais está rodando não está medindo o que diz medir.
     */
    protected long cheapestStatementCount(Runnable query) {
        long cheapest = Long.MAX_VALUE;
        for (int attempt = 0; attempt < STATEMENT_SAMPLES; attempt++) {
            Statistics statistics = statisticsOfAQuietDatabase();
            query.run();
            cheapest = Math.min(cheapest, statistics.getPrepareStatementCount());
        }
        return cheapest;
    }

    /**
     * TRÊS execuções, e o número não é chute: com uma não há mínimo, com duas uma interferência em
     * cada estraga as duas. Três é a menor quantidade que sobrevive a duas janelas azaradas, e a
     * consulta é de leitura — repeti-la não muda estado nenhum.
     */
    private static final int STATEMENT_SAMPLES = 3;
}
