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

import at.meks.quarkiverse.axon.transaction.runtime.QuarkusTransactionManager;
import dev.manuelantunes.axonposts.domain.post.Post;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O guarda da configuração que a extensão monta: duas coisas que ela decide por default, que este projeto
 * decide diferente, e que quebram <b>em silêncio</b> se a substituição parar de valer.
 *
 * <h2>Por que um teste, e não confiar no {@code @DefaultBean}</h2>
 * Porque as duas substituições funcionam por ausência: a extensão declara um bean padrão e cede a vez se
 * existir outro do mesmo tipo. Apagar {@link JtaTransactionManager} ou {@link EventSourcedEntities}, ou
 * trocar a anotação de escopo deles, não quebra compilação nenhuma — a extensão simplesmente volta ao
 * padrão dela, e o padrão dela está errado para este projeto:
 * <ul>
 *   <li>sem o transaction manager, o padrão é {@code NoTransactionManager}: o append do evento e o
 *       {@code merge} do read model deixam de commitar juntos;</li>
 *   <li>sem o mapa de ids, o padrão é {@code String}: todo command falha ao reidratar o agregado.</li>
 * </ul>
 * O segundo caso apareceria nos testes ponta a ponta; o primeiro, não necessariamente — o
 * {@code @Transactional} do Panache abriria a própria transação e a maioria dos testes passaria. É esse
 * que justifica a classe.
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

    @Test
    void theTransactionManagerIsTheBridgeToJta() {
        assertThat(axon.getComponent(TransactionManager.class))
                .as("o default da extensão é NoTransactionManager, e ele não commita nada junto")
                .isInstanceOf(QuarkusTransactionManager.class);
    }

    @Test
    void everyEntityIsRegisteredUnderItsOwnIdType() {
        StateManager entities = axon.getComponent(StateManager.class);

        assertThat(entities.repository(Post.class, PostId.class)).isNotNull();
        assertThat(entities.repository(Tag.class, TagId.class)).isNotNull();
        assertThat(entities.repository(User.class, UserId.class)).isNotNull();
    }

    /**
     * <b>Todo pacote com {@code @EventHandler} tem de estar em
     * {@code quarkus.axon.subscribingprocessor.namespaces}.</b> É o teste que responde "e quando entrar um
     * event handler de outro agregado?".
     *
     * <h2>Os dois jeitos de errar, e por que só um precisa de teste</h2>
     * A extensão agrupa event handlers por {@code @Namespace} lido da classe, caindo no <b>nome do
     * pacote</b>. O que está na propriedade vai para o processor subscribing; <b>todo o resto vai para um
     * pooled</b>, que é assíncrono. Então:
     * <ul>
     *   <li><b>pacote novo esquecido na lista</b> — a aplicação sobe, os handlers rodam, e a projeção vira
     *       eventualmente consistente sem ninguém pedir: o {@code createPost} passa a responder antes da
     *       tag, e a subscription chega depois da resposta. Silêncio total. É este que o teste pega;</li>
     *   <li><b>pacote na lista sem handler nenhum</b> — {@code getEventhandlers} faz
     *       {@code map(mapa::get).flatMap(Collection::stream)} sobre um {@code null}, e a aplicação
     *       <b>não sobe</b>: {@code NullPointerException} na partida. Não precisa de teste porque
     *       qualquer {@code @QuarkusTest} já falha — mas precisa ser sabido, porque impede declarar o
     *       pacote <i>antes</i> de escrever o primeiro handler dele. A ordem é: handler primeiro,
     *       propriedade depois.</li>
     * </ul>
     */
    @Test
    void everyPackageWithAnEventHandlerRunsInTheSubscribingProcessor() {
        assertThat(packagesWithEventHandlers())
                .as("pacote com @EventHandler fora de quarkus.axon.subscribingprocessor.namespaces cai "
                        + "num processor pooled (assíncrono) sem avisar — acrescente-o à propriedade")
                .isSubsetOf(union(subscribingNamespaces, MANUALLY_CONFIGURED));
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
