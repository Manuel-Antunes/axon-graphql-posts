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

@QuarkusTest
class TaggingWiringTest {
    private static final String APPLICATION_PACKAGE = "dev.manuelantunes.axonposts.tagging.application";

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
                .as("sem o quarkus-axon-transaction o append e a linha do inbox deixam de ser um commit só")
                .isInstanceOf(QuarkusTransactionManager.class);
    }

    @Test
    void thePostIsRegisteredUnderItsOwnIdType() {
        assertThat(axon.getComponent(StateManager.class).repository(Post.class, PostId.class))
                .as("este serviço não tem agregado próprio: ele trabalha com o Post de libs/posts")
                .isNotNull();
    }

    @Test
    void everyPackageWithAnEventHandlerIsAssignedToTheSubscribingProcessor() {
        assertThat(packagesWithEventHandlers())
                .as("com o PooledEventProcessingConfigurer excluído, um pacote fora desta lista não cai "
                        + "num processor assíncrono — ele não roda, e a saga para na versão 1")
                .isSubsetOf(subscribingNamespaces);
    }

    @Test
    void theListenersOnlyHandOverToTheIngestion() {
        for (Class<?> listener : List.of(PostPreCreatedListener.class, PostChangesListener.class)) {
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
