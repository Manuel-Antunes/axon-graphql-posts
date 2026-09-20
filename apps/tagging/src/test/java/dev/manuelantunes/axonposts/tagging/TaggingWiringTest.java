package dev.manuelantunes.axonposts.tagging;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.tagging.interfaces.messaging.PostChangesListener;
import dev.manuelantunes.axonposts.tagging.interfaces.messaging.PostPreCreatedListener;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import org.axonframework.common.configuration.Configuration;
import at.meks.quarkiverse.axon.transaction.runtime.QuarkusTransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.modelling.StateManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * As decisões de fiação DESTE serviço — as que a extensão de Quarkus decidiria diferente, e que falham
 * em SILÊNCIO quando alguém as desfaz.
 *
 * <h2>Por que um arquivo só para isto</h2>
 * Porque nenhuma delas quebra compilação, e quase nenhuma quebra a partida. O serviço sobe, consome
 * mensagens e não decide nada — ou decide duas vezes, ou decide fora da transação. É o irmão do
 * {@code AxonWiringTest} do outro app, com os guardas que são próprios daqui.
 */
@QuarkusTest
class TaggingWiringTest {

    /** O pacote raiz deste serviço: nada fora dele interessa à varredura. */
    private static final String APPLICATION_PACKAGE = "dev.manuelantunes.axonposts.tagging.application";

    @Inject
    Configuration axon;

    @Inject
    BeanManager beans;

    @Inject
    @ConfigProperty(name = "quarkus.axon.subscribingprocessor.namespaces")
    List<String> subscribingNamespaces;

    /**
     * O {@code TransactionManager} é a ponte para o JTA, e a substituição funciona por AUSÊNCIA.
     * <p>
     * A extensão declara um {@code NoTransactionManager} como {@code @DefaultBean} e cede a vez a quem
     * existir — então tirar o {@code quarkus-axon-transaction} do pom não quebra compilação nenhuma: o
     * evento simplesmente deixa de commitar junto com a linha do inbox, e a garantia de que reentrega é
     * descartada some com ele.
     */
    @Test
    void theTransactionManagerIsTheBridgeToJta() {
        assertThat(axon.getComponent(TransactionManager.class))
                .as("sem o quarkus-axon-transaction o append e a linha do inbox deixam de ser um commit só")
                .isInstanceOf(QuarkusTransactionManager.class);
    }

    /**
     * O tipo do id do {@code Post} vem do mapa de {@code libs/platform}, e não de uma anotação.
     * <p>
     * Apagar aquele mapa não quebra compilação — volta o {@code String} como id, e o
     * {@code @InjectEntity} do command handler passa a não encontrar o agregado.
     */
    @Test
    void thePostIsRegisteredUnderItsOwnIdType() {
        assertThat(axon.getComponent(StateManager.class).repository(Post.class, PostId.class))
                .as("este serviço não tem agregado próprio: ele trabalha com o Post de libs/posts")
                .isNotNull();
    }

    /**
     * <b>Todo pacote com {@code @EventHandler} tem de estar em {@code subscribingprocessor.namespaces}.</b>
     *
     * <h3>Aqui o esquecimento custa mais caro que no outro serviço</h3>
     * Lá, um pacote fora da lista cai num pooled anônimo e vira eventualmente consistente. Aqui o
     * {@code PooledEventProcessingConfigurer} está em {@code quarkus.arc.exclude-types} — então não há
     * pooled para onde cair, e o handler simplesmente <b>não roda</b>. A saga para na versão 1 e nada
     * no log diz por quê.
     *
     * <h3>A ordem é handler primeiro, propriedade depois</h3>
     * O inverso — namespace listado sem nenhum handler — faz a aplicação NÃO SUBIR, com
     * {@code NullPointerException} na partida. Esse não precisa de teste: qualquer {@code @QuarkusTest}
     * já falha.
     */
    @Test
    void everyPackageWithAnEventHandlerIsAssignedToTheSubscribingProcessor() {
        assertThat(packagesWithEventHandlers())
                .as("com o PooledEventProcessingConfigurer excluído, um pacote fora desta lista não cai "
                        + "num processor assíncrono — ele não roda, e a saga para na versão 1")
                .isSubsetOf(subscribingNamespaces);
    }

    /**
     * UM LISTENER ENTREGA E SAI — regra 4 das camadas, e ela não tem compilador que a confira.
     * <p>
     * Porta de entrada é APRESENTAÇÃO: um {@code @Incoming} é um endereço, como um {@code @GraphQLApi}
     * é um caminho. Ele não alcança repositório nem decide regra — entrega a mensagem ao mecanismo de
     * ingestão e sai, como um resolver entrega ao command gateway.
     * <p>
     * A asserção é sobre os CAMPOS: um listener que tivesse ganhado um repositório, um gateway ou um
     * {@code Clock} teria deixado de ser endereço e virado aplicação, e é isso que se quer pegar — antes
     * de a regra estar escrita em dois lugares.
     */
    @Test
    void theListenersOnlyHandOverToTheIngestion() {
        for (Class<?> listener : List.of(PostPreCreatedListener.class, PostChangesListener.class)) {
            // Só os campos de INSTÂNCIA escritos à mão: o `CHANNEL` é `static` (o nome do canal, que é
            // endereço e não colaborador) e o ArC acrescenta sintéticos ao subclassear para o proxy.
            List<String> collaborators = Arrays.stream(listener.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !field.isSynthetic())
                    .map(field -> field.getType().getSimpleName())
                    .toList();

            assertThat(collaborators)
                    .as("%s é porta de entrada: ela entrega ao mecanismo de ingestão e sai — um "
                            + "repositório ou um gateway aqui seria regra vazando para a apresentação",
                            listener.getSimpleName())
                    .containsExactly("ChannelEventIngestion");
        }
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
