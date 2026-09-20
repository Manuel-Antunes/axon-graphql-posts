package dev.manuelantunes.axonposts.infrastructure.axon;

import java.lang.reflect.Method;
import java.util.List;
import java.util.HashSet;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.modelling.StateManager;
import org.junit.jupiter.api.Test;

import at.meks.quarkiverse.axon.runtime.customizations.AxonMetricsConfigurer;
import at.meks.quarkiverse.axon.transaction.runtime.QuarkusTransactionManager;
import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.infrastructure.messaging.ChannelEventIngestion;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O guarda da configuração que a extensão monta: o que ela decide por default, que este projeto decide
 * diferente, e que quebra <b>em silêncio</b> se a substituição parar de valer.
 *
 * <h2>Por que um teste, e não confiar no {@code @DefaultBean}</h2>
 * Porque as substituições funcionam por ausência: a extensão declara um bean padrão e cede a vez se
 * existir outro do mesmo tipo. Apagar {@link JtaTransactionManager}, {@link EventSourcedEntities} ou
 * {@link AxonMetrics} — ou trocar a anotação de escopo deles — não quebra compilação nenhuma. A extensão
 * volta ao padrão dela, e o padrão dela está errado para este projeto:
 * <ul>
 *   <li>sem o transaction manager, o padrão é {@code NoTransactionManager}: o append do evento e o
 *       {@code merge} do read model deixam de commitar juntos;</li>
 *   <li>sem o mapa de ids, o padrão é {@code String}: todo command falha ao reidratar o agregado;</li>
 *   <li>sem as métricas, o único sinal do que acontece dentro dos buses desaparece nos DOIS serviços.</li>
 * </ul>
 * Só o segundo apareceria nos testes ponta a ponta; os outros dois passam calados.
 * <p>
 * A classe guarda também um invariante que não é bean nenhum: a ingestão <b>abre</b> a própria transação,
 * e uma anotação a mais faria a unidade de trabalho do Axon juntar em vez de abrir. Ver
 * {@link #theIngestionOwnsItsOwnTransaction()}.
 */
@QuarkusTest
class AxonWiringTest {

    /** O pacote raiz da aplicação: nada fora dele interessa a esta varredura. */
    private static final String APPLICATION_PACKAGE = "dev.manuelantunes.axonposts.application";

    @Inject
    Configuration axon;

    @Inject
    BeanManager beans;

    @Inject
    @ConfigProperty(name = "quarkus.axon.subscribingprocessor.namespaces")
    List<String> subscribingNamespaces;

    /**
     * Os namespaces que rodam num processor <b>pooled streaming</b> DECLARADO — e não por esquecimento.
     * <p>
     * Hoje há um: {@code application.post.event}, onde moram os handlers que NOTIFICAM os assinantes.
     * Eles precisam ler do event store para enxergar o que os outros containers apendaram — ver o
     * {@code package-info} daquele pacote.
     */
    @ConfigProperty(name = "quarkus.axon.pooledprocessor.post-subscriptions.namespaces")
    List<String> pooledNamespaces;

    @Test
    void theTransactionManagerIsTheBridgeToJta() {
        assertThat(axon.getComponent(TransactionManager.class))
                .as("o default da extensão é NoTransactionManager, e ele não commita nada junto")
                .isInstanceOf(QuarkusTransactionManager.class);
    }

    /**
     * <b>As métricas do Axon existem.</b> Terceira substituição por ausência desta classe, e a mais
     * silenciosa das três: o {@code NoMetricsConfigurer} da extensão é {@code @DefaultBean}, então apagar
     * {@code AxonMetrics} de {@code libs/platform} — ou trocar a anotação de escopo dela — não quebra
     * compilação, não derruba a partida e não falha nenhum outro teste. A aplicação sobe idêntica e
     * simplesmente para de emitir métrica, nos DOIS serviços.
     * <p>
     * O que se perde é o único sinal do que acontece dentro do command/event bus, porque span ali não
     * existe: o Axon 5 ainda não tem tracing.
     */
    @Test
    void theAxonMetricsAreWiredToOpenTelemetry() {
        assertThat(beans.resolve(beans.getBeans(AxonMetricsConfigurer.class, Any.Literal.INSTANCE)))
                .as("o default da extensão é NoMetricsConfigurer, e com ele nenhuma métrica do Axon sai")
                .isNotNull()
                .extracting(Bean::getBeanClass)
                .isEqualTo(AxonMetrics.class);
    }

    /**
     * <b>A ingestão abre a própria transação — e por isso NÃO pode levar {@code @Transactional}.</b>
     *
     * <h2>Por que a ausência de uma anotação merece um teste</h2>
     * Porque pôr {@code @Transactional} de volta em {@code ChannelEventIngestion.ingest} (ou no método do
     * listener que a chama) não quebra <b>nada</b> que esta suíte veja. A atomicidade entre a linha do
     * inbox e o append continua valendo, todos os 155 testes passam, e a aplicação sobe idêntica.
     * <p>
     * O que quebra é a subscription, e só no caminho entre PROCESSOS. Com uma transação JTA já aberta, a
     * unidade de trabalho do Axon <b>junta</b> em vez de abrir; o {@code SimpleQueryBus} adia os updates
     * para o after-commit do {@code ProcessingContext}, e esse after-commit passa a disparar com a
     * transação ainda aberta. O assinante lê o banco noutra thread, dentro dela, e a transação aborta:
     * {@code CheckedAction::check - atomic action ... aborting with 2 threads active!}
     * <p>
     * Medido nos dois sentidos com {@code docker/e2e/run.sh}: <b>11 de 12</b> com a anotação, <b>12 de
     * 12</b> sem ela. Este teste é o que traz aquela medição para dentro do {@code ./mvnw test}.
     * <p>
     * A varredura sobe a hierarquia por {@code getDeclaredMethods()} pelo mesmo motivo que
     * {@link #packagesWithEventHandlers()}: {@code getMethods()} não enxerga método pacote-visível.
     */
    @Test
    void theIngestionOwnsItsOwnTransaction() {
        assertThat(transactionalMethodsOf(ChannelEventIngestion.class))
                .as("com @Transactional a unidade de trabalho do Axon JUNTA em vez de abrir, e o "
                        + "after-commit dela deixa de ser depois do commit — a subscription morre, e só "
                        + "entre processos")
                .isEmpty();
    }

    private static Set<String> transactionalMethodsOf(Class<?> type) {
        Set<String> annotated = new TreeSet<>();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Transactional.class)) {
                    annotated.add(current.getSimpleName() + "." + method.getName());
                }
            }
        }
        return annotated;
    }

    @Test
    void everyEntityIsRegisteredUnderItsOwnIdType() {
        StateManager entities = axon.getComponent(StateManager.class);

        assertThat(entities.repository(Post.class, PostId.class)).isNotNull();
        assertThat(entities.repository(Tag.class, TagId.class)).isNotNull();
        assertThat(entities.repository(User.class, UserId.class)).isNotNull();
    }

    /**
     * <b>Todo pacote com {@code @EventHandler} tem de estar DECLARADO em algum processor</b> — o
     * subscribing ou um pooled nomeado. É o teste que responde "e quando entrar um event handler de
     * outro agregado?".
     *
     * <h2>Por que a asserção deixou de ser "tem de ser subscribing"</h2>
     * Porque passou a existir um pacote que <b>precisa</b> ser pooled: {@code application.post.event} lê
     * do event store para enxergar o que os OUTROS containers apendaram, que é o único jeito de uma
     * subscription funcionar com mais de uma instância. Tratá-lo como exceção numa constante diria
     * "este escapou do guarda"; lê-lo da configuração diz o que é verdade — ele está declarado, só que
     * noutro processor.
     * <p>
     * É também por isso que o guarda não pode ser "tudo o que projeta é subscribing": o que decide o
     * processor de um pacote é a ENTREGA que as reações dele precisam, e este projeto tem as duas.
     * {@code application.post.projection} grava uma vez, na transação do append;
     * {@code application.post.event} avisa em todo container, fora dela.
     * <p>
     * O guarda não perdeu dente nenhum: um pacote que não esteja em NENHUMA das duas listas continua
     * falhando, e é esse o caso silencioso que importa.
     *
     * <h2>Os dois jeitos de errar, e por que só um precisa de teste</h2>
     * A extensão agrupa event handlers por {@code @Namespace} lido da classe, caindo no <b>nome do
     * pacote</b>. O que está na propriedade vai para o processor subscribing; <b>todo o resto vai para um
     * pooled</b>, que é assíncrono. Então:
     * <ul>
     *   <li><b>pacote novo esquecido na lista</b> — a aplicação sobe, os handlers rodam, e a projeção vira
     *       eventualmente consistente sem ninguém pedir: o {@code createPost} passa a responder antes da
     *       tag. Pior, o pooled anônimo vem com token store JPA, que é o oposto do que um fan-out
     *       precisa — um container reclama o segmento e os outros ficam sem ver nada. Silêncio total nos
     *       dois casos. É este que o teste pega;</li>
     *   <li><b>pacote na lista sem handler nenhum</b> — {@code getEventhandlers} faz
     *       {@code map(mapa::get).flatMap(Collection::stream)} sobre um {@code null}, e a aplicação
     *       <b>não sobe</b>: {@code NullPointerException} na partida. Não precisa de teste porque
     *       qualquer {@code @QuarkusTest} já falha — mas precisa ser sabido, porque impede declarar o
     *       pacote <i>antes</i> de escrever o primeiro handler dele. A ordem é: handler primeiro,
     *       propriedade depois.</li>
     * </ul>
     */
    @Test
    void everyPackageWithAnEventHandlerIsAssignedToAProcessor() {
        assertThat(packagesWithEventHandlers())
                .as("pacote com @EventHandler que não está nem em subscribingprocessor.namespaces nem "
                        + "num pooledprocessor nomeado cai num pooled anônimo (assíncrono, com token "
                        + "store JPA) sem avisar — declare-o numa das duas propriedades")
                .isSubsetOf(union(union(subscribingNamespaces, pooledNamespaces), MANUALLY_CONFIGURED));
    }

    /**
     * Pacotes cujo processor é montado à mão, e portanto legitimamente fora da propriedade.
     *
     * <h3>Hoje é VAZIO, e isso é uma notícia</h3>
     * Havia um item aqui: {@code application.post.tagging}, que tinha um processor próprio alimentado
     * por uma fila — porque a extensão não sabe expressar dois processors subscribing
     * ({@code subscribingprocessor.name} é singular). Aquele processor deixou de existir quando a
     * entrada da integração passou a apendar no event store em vez de alimentar um processor
     * diretamente: com o store como fonte, o processor de sempre serve, e não há nada a montar à mão.
     * <p>
     * Com a lista vazia o guarda voltou a ser <b>total</b> — todo pacote com {@code @EventHandler} tem
     * de estar na propriedade. A constante fica porque ela é o lugar onde uma exceção futura seria
     * declarada, e declarada com justificativa; apagá-la faria a próxima ser um {@code @Disabled}.
     */
    private static final Set<String> MANUALLY_CONFIGURED = Set.of();

    private static Set<String> union(Collection<String> first, Collection<String> second) {
        Set<String> all = new HashSet<>(first);
        all.addAll(second);
        return all;
    }

    /**
     * Varre o {@link BeanManager} atrás de classes com pelo menos um {@code @EventHandler}.
     * <p>
     * {@code getDeclaredMethods()} subindo a hierarquia, e não {@code getMethods()}: este último não
     * enxerga método de classe não-pública nem método pacote-visível, e um handler assim existiria sem
     * nunca ser encontrado — o teste passaria justamente no caso que ele existe para pegar.
     */
    private Set<String> packagesWithEventHandlers() {
        Set<String> packages = new TreeSet<>();
        for (Bean<?> bean : beans.getBeans(Object.class, Any.Literal.INSTANCE)) {
            Class<?> type = bean.getBeanClass();
            if (type.getName().startsWith(APPLICATION_PACKAGE) && hasEventHandler(type)) {
                packages.add(type.getPackageName());
            }
        }
        return packages;
    }

    private static boolean hasEventHandler(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isAnnotationPresent(EventHandler.class)) {
                    return true;
                }
            }
        }
        return false;
    }
}
