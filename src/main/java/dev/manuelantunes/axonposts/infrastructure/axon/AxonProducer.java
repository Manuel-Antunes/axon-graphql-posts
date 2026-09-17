package dev.manuelantunes.axonposts.infrastructure.axon;

import java.time.Clock;
import java.util.List;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.jboss.logging.Logger;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import io.quarkus.arc.ClientProxy;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Singleton;

/**
 * A infraestrutura do Axon 5 nesta aplicação: <b>escolhas de deploy</b>, e nada de negócio. Nenhuma regra
 * de Post aparece neste arquivo, e trocar qualquer uma das decisões abaixo não muda uma linha de domínio
 * ou de aplicação.
 *
 * <h2>Por que existe um produtor, e não um starter</h2>
 * O Axon 5 publica {@code axon-spring-boot-starter}, que monta a configuração a partir do
 * {@code ApplicationContext}. Não há equivalente para Quarkus — e não precisa haver: o
 * {@link EventSourcingConfigurer} é a API <b>do núcleo</b>, sem dependência de framework nenhum. O que o
 * starter do Spring faz por dentro (descobrir handlers, registrar entidades, publicar os gateways como
 * beans) são as três coisas que esta classe faz explicitamente, em cinquenta linhas legíveis.
 * <p>
 * A troca é honesta: perde-se a mágica, ganha-se poder apontar para o lugar onde cada decisão foi tomada.
 *
 * <h2>As decisões</h2>
 * <ul>
 *   <li><b>Event store em memória</b>: os eventos vivem só no processo (somem no restart); o Postgres
 *       guarda apenas o read model, e nenhuma tabela do Axon existe lá. Trocar por um event store
 *       persistente é trocar {@link #eventStorageEngine()}, e mais nada.</li>
 *   <li><b>Sem Axon Server</b>: o connector não está no classpath, então o Axon usa
 *       {@code SimpleCommandBus} + {@code SimpleQueryBus} locais — os dois executam o handler na thread
 *       que despacha, que é a razão de os resolvers GraphQL saírem do event-loop (ver
 *       {@code GraphQlExecution}).</li>
 *   <li><b>Transações JTA</b>: {@link JtaTransactionManager} registrado como componente faz o
 *       {@code UnitOfWorkFactory} padrão abrir uma transação por {@code ProcessingContext} — é o que faz
 *       o append do evento e o {@code merge} do read model commitarem juntos.</li>
 *   <li><b>Processors subscribing</b>: todo event processor descoberto roda em modo
 *       <i>subscribing</i> — mesma thread, mesma transação do command. O default do Axon 5 é pooled
 *       streaming (assíncrono); é em {@link #processorModules} que se troca para ver consistência
 *       eventual.</li>
 *   <li>{@link Clock} como bean: injetado nos commands para carimbar os eventos, e substituível por um
 *       clock fixo em teste.</li>
 * </ul>
 *
 * <h2>{@code ClientProxy.unwrap}, e por que ele não é opcional</h2>
 * O Axon monta os componentes anotados lendo, por reflexão, os métodos de {@code instance.getClass()}.
 * Um bean {@code @ApplicationScoped} é entregue pelo ArC como <i>client proxy</i>: uma subclasse gerada
 * que <b>sobrescreve</b> todo método público para delegar — e método sobrescrito não herda as anotações
 * do método que sobrescreve. Sem o unwrap, o Axon olharia para a subclasse, não acharia
 * {@code @CommandHandler} nenhum e registraria um componente vazio: a aplicação sobe, e o primeiro
 * command falha com "no handler for ...".
 */
@ApplicationScoped
public class AxonProducer {

    private static final Logger log = Logger.getLogger(AxonProducer.class);

    /**
     * Relógio de aplicação. Bean para poder ser fixo em teste — é dele que sai o {@code occurredAt} de
     * todo evento.
     */
    @Produces
    @Singleton
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Event store <b>em memória</b>: os eventos existem enquanto o processo existir.
     * <p>
     * A consequência a ter em mente no dia a dia é a mesma do projeto Spring, e o live reload do Quarkus
     * a torna mais frequente: cada reinício apaga os eventos enquanto as <b>linhas</b> continuam no
     * Postgres. Um post criado antes do reload segue respondendo em {@code post(id:)} e passa a dar
     * {@code NOT_FOUND} no {@code updatePost}, que reidrata o agregado do stream. Não é bug; é a POC.
     */
    @Produces
    @Singleton
    public EventStorageEngine eventStorageEngine() {
        return new InMemoryEventStorageEngine();
    }

    /**
     * Monta a {@link AxonConfiguration} da aplicação inteira.
     *
     * @param beans           usado para resolver a instância de cada componente de mensagem — dentro do
     *                        {@code ComponentBuilder}, e não aqui, para que a criação dos beans aconteça
     *                        depois de a configuração estar montada
     * @param beanManager     de onde sai a lista de componentes; ver {@link AxonHandlerLookup}
     * @param transactionManager a ponte para o JTA do Quarkus
     * @param eventStorageEngine onde os eventos são guardados
     */
    @Produces
    @Singleton
    public AxonConfiguration axonConfiguration(@Any Instance<Object> beans,
                                               BeanManager beanManager,
                                               TransactionManager transactionManager,
                                               EventStorageEngine eventStorageEngine) {

        AxonHandlerLookup lookup = new AxonHandlerLookup(beanManager);

        EventSourcingConfigurer configurer = EventSourcingConfigurer.create()
                .registerEventStorageEngine(configuration -> eventStorageEngine)
                .componentRegistry(registry -> registry.registerComponent(
                        TransactionManager.class, configuration -> transactionManager));

        // As entidades event-sourced. O tipo do id vem daqui e não de uma anotação: no starter do Spring
        // ele é o `idType` do @EventSourced, que só existe para o scan ter onde lê-lo.
        configurer.registerEntity(EventSourcedEntityModule.autodetected(PostId.class, Post.class))
                  .registerEntity(EventSourcedEntityModule.autodetected(TagId.class, Tag.class))
                  .registerEntity(EventSourcedEntityModule.autodetected(UserId.class, User.class));

        lookup.commandHandlingComponents().forEach((packageName, types) -> {
            log.debugf("command handling module [%s]: %s", packageName, simpleNames(types));
            CommandHandlingModule.CommandHandlerPhase module =
                    CommandHandlingModule.named("CommandHandling[" + packageName + "]").commandHandlers();
            types.forEach(type -> module.autodetectedCommandHandlingComponent(
                    configuration -> resolve(beans, type)));
            configurer.registerCommandHandlingModule(module);
        });

        lookup.queryHandlingComponents().forEach((packageName, types) -> {
            log.debugf("query handling module [%s]: %s", packageName, simpleNames(types));
            QueryHandlingModule.QueryHandlerPhase module =
                    QueryHandlingModule.named("QueryHandling[" + packageName + "]").queryHandlers();
            types.forEach(type -> module.autodetectedQueryHandlingComponent(
                    configuration -> resolve(beans, type)));
            configurer.registerQueryHandlingModule(module);
        });

        processorModules(configurer, lookup, beans);

        return configurer.build();
    }

    /**
     * Um {@link EventProcessorModule} <b>subscribing</b> por namespace encontrado.
     *
     * <h3>Por que subscribing importa aqui</h3>
     * Em modo subscribing os event handlers executam na mesma thread e no mesmo
     * {@code ProcessingContext} (e transação) do command. É isso que garante que o {@code save} do
     * command já aconteceu quando o handler roda, que o emit para as subscriptions sai uma única vez
     * depois do commit, e que a mutation {@code createPost} já responde com a tag padrão atribuída —
     * porque o {@code onAfterCommit} do {@code AssignDefaultTagOnPostCreated} é esperado antes de o
     * processamento terminar.
     * <p>
     * Trocar a linha por {@code EventProcessorModule.pooledStreaming(name)} torna a projeção assíncrona:
     * a mutation passa a responder antes da tag, e o cliente a vê chegar pelo {@code onPostUpdated}. É a
     * diferença entre consistência imediata e eventual, numa palavra.
     */
    private static void processorModules(EventSourcingConfigurer configurer,
                                         AxonHandlerLookup lookup,
                                         Instance<Object> beans) {
        lookup.eventHandlingComponents().forEach((processorName, types) -> {
            log.debugf("event processor [%s] (subscribing): %s", processorName, simpleNames(types));

            configurer.componentRegistry(registry -> registry.registerModule(
                    EventProcessorModule.subscribing(processorName)
                            .eventHandlingComponents(phase -> components(phase, types, beans))
                            // O event source é obrigatório e NÃO tem default quando o módulo é
                            // registrado avulso: o `SubscribingEventProcessorsConfigurer` só o injeta
                            // nos processors criados por dentro dele, procurando um componente do tipo
                            // SubscribableEventSource — e o registry do Axon indexa por tipo exato, então
                            // o EventStore (que É um SubscribableEventSource) registrado sob EventStore
                            // não é encontrado por aquela busca.
                            //
                            // Sem esta linha a aplicação sobe, os handlers aparecem registrados no log, e
                            // simplesmente nada acontece: nenhum evento chega à projeção, o post nasce sem
                            // a tag padrão e as subscriptions ficam mudas. É o tipo de falha que só um
                            // teste ponta a ponta pega.
                            .customized((configuration, processorConfiguration) -> processorConfiguration
                                    .eventSource(configuration.getComponent(EventStore.class)))
                            // `.build()` não é cerimônia: é ele que registra o processor e os handlers no
                            // registry do módulo. Registrar o módulo sem construí-lo compila, sobe e
                            // produz um processor que nunca é instanciado — mesmo sintoma da linha acima,
                            // silêncio absoluto. É o que o `DefaultProcessorModuleFactory` do módulo
                            // Spring faz, e o que só se descobre lendo a fonte dele.
                            .build()));
        });
    }

    /**
     * O primeiro componente entra pelo {@code RequiredComponentPhase} e os demais pelo
     * {@code AdditionalComponentPhase} — a API do Axon usa os dois tipos para garantir, em tempo de
     * compilação, que um processor não nasça sem handler nenhum.
     */
    private static EventHandlingComponentsConfigurer.CompletePhase components(
            EventHandlingComponentsConfigurer.RequiredComponentPhase phase,
            List<Class<?>> types,
            Instance<Object> beans) {

        EventHandlingComponentsConfigurer.AdditionalComponentPhase current =
                phase.autodetected(types.getFirst().getName(), configuration -> resolve(beans, types.getFirst()));

        for (Class<?> type : types.subList(1, types.size())) {
            current = current.autodetected(type.getName(), configuration -> resolve(beans, type));
        }
        return current;
    }

    /** O bean, sem o proxy do ArC por cima — ver o javadoc da classe. */
    private static Object resolve(Instance<Object> beans, Class<?> type) {
        return ClientProxy.unwrap(beans.select(type).get());
    }

    private static String simpleNames(List<Class<?>> types) {
        return String.join(", ", types.stream().map(Class::getSimpleName).toList());
    }

    /**
     * O {@code CommandGateway} montado pela configuração, publicado como bean para que a camada de
     * aplicação e os resolvers o injetem como qualquer outro.
     * <p>
     * É o que o {@code SpringComponentRegistry} faz em runtime na versão Spring — e a razão de lá todo
     * ponto de injeção de gateway carregar um {@code @SuppressWarnings}: o bean existe, mas nenhuma
     * declaração estática o anuncia, e a inspeção do IDE acusa erro. Aqui ele é um {@code @Produces}
     * comum, visível para o compilador, para o ArC e para o IDE.
     */
    @Produces
    @Singleton
    public CommandGateway commandGateway(AxonConfiguration configuration) {
        return configuration.getComponent(CommandGateway.class);
    }

    @Produces
    @Singleton
    public QueryGateway queryGateway(AxonConfiguration configuration) {
        return configuration.getComponent(QueryGateway.class);
    }

    /**
     * O emissor das subscription queries. Os event handlers o recebem por <b>parâmetro</b> (o Axon 5 o
     * resolve já ligado ao {@code ProcessingContext} do evento); este bean existe para quem precisar dele
     * fora de um handler.
     */
    @Produces
    @Singleton
    public QueryUpdateEmitter queryUpdateEmitter(AxonConfiguration configuration) {
        return configuration.getComponent(QueryUpdateEmitter.class);
    }

    /**
     * O ciclo de vida do Axon amarrado ao do Quarkus.
     * <p>
     * Receber a {@link AxonConfiguration} como parâmetro do observer é o que força a configuração a ser
     * montada <b>na partida</b>, e não na primeira requisição: um erro de wiring aparece no
     * {@code quarkus:dev} na hora, e não quando alguém manda a primeira mutation.
     */
    void start(@Observes StartupEvent event, AxonConfiguration configuration) {
        configuration.start();
        log.info("Axon iniciado: event store em memória, processors subscribing");
    }

    void stop(@Observes ShutdownEvent event, AxonConfiguration configuration) {
        configuration.shutdown();
    }
}
