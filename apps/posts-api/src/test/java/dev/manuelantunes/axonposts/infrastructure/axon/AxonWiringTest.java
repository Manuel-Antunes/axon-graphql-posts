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

@QuarkusTest
class AxonWiringTest {
    private static final String APPLICATION_PACKAGE = "dev.manuelantunes.axonposts.application";

    @Inject
    Configuration axon;

    @Inject
    BeanManager beans;

    @Inject
    @ConfigProperty(name = "quarkus.axon.subscribingprocessor.namespaces")
    List<String> subscribingNamespaces;

    @ConfigProperty(name = "quarkus.axon.pooledprocessor.post-subscriptions.namespaces")
    List<String> pooledNamespaces;

    @Test
    void theTransactionManagerIsTheBridgeToJta() {
        assertThat(axon.getComponent(TransactionManager.class))
                .as("o default da extensão é NoTransactionManager, e ele não commita nada junto")
                .isInstanceOf(QuarkusTransactionManager.class);
    }

    @Test
    void theAxonMetricsAreWiredToOpenTelemetry() {
        assertThat(beans.resolve(beans.getBeans(AxonMetricsConfigurer.class, Any.Literal.INSTANCE)))
                .as("o default da extensão é NoMetricsConfigurer, e com ele nenhuma métrica do Axon sai")
                .isNotNull()
                .extracting(Bean::getBeanClass)
                .isEqualTo(AxonMetrics.class);
    }

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

    @Test
    void everyPackageWithAnEventHandlerIsAssignedToAProcessor() {
        assertThat(packagesWithEventHandlers())
                .as("pacote com @EventHandler que não está nem em subscribingprocessor.namespaces nem "
                        + "num pooledprocessor nomeado cai num pooled anônimo (assíncrono, com token "
                        + "store JPA) sem avisar — declare-o numa das duas propriedades")
                .isSubsetOf(union(union(subscribingNamespaces, pooledNamespaces), MANUALLY_CONFIGURED));
    }

    private static final Set<String> MANUALLY_CONFIGURED = Set.of();

    private static Set<String> union(Collection<String> first, Collection<String> second) {
        Set<String> all = new HashSet<>(first);
        all.addAll(second);
        return all;
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
