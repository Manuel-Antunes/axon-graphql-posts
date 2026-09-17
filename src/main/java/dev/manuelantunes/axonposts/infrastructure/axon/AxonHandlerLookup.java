package dev.manuelantunes.axonposts.infrastructure.axon;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

/**
 * Descobre, entre os beans CDI da aplicação, quais são componentes de mensagem do Axon — e de que tipo.
 *
 * <h2>Por que isto existe</h2>
 * O {@code axon-spring-boot-starter} traz um {@code MessageHandlerLookup}: um
 * {@code BeanFactoryPostProcessor} que varre as definições de bean procurando métodos
 * {@code @CommandHandler} / {@code @QueryHandler} / {@code @EventHandler} e registra cada grupo no
 * {@code ComponentRegistry} do Axon. Não existe starter equivalente para Quarkus, então esta classe faz o
 * mesmo trabalho sobre o {@link BeanManager}.
 * <p>
 * O ganho de fazer assim, em vez de listar os handlers à mão no {@code AxonProducer}, é a propriedade que
 * o projeto Spring documenta e que seria a primeira a se perder na conversão: <b>uma mensagem nova é um
 * arquivo novo</b>. Escrever a classe do command já a registra; não há uma segunda lista para esquecer de
 * atualizar — e um handler que ninguém registrou é um bug que só aparece em runtime.
 *
 * <h2>Varre classes, não instâncias</h2>
 * A inspeção é sobre {@link Bean#getBeanClass()} e os métodos declarados nela. Nada é instanciado aqui: o
 * {@code AxonProducer} resolve a instância dentro do {@code ComponentBuilder}, que só roda quando o Axon
 * monta o módulo. É o mesmo cuidado que o lookup do Spring toma ao trabalhar sobre {@code BeanDefinition}
 * em vez de beans prontos — resolver cedo demais criaria ciclo com quem depende da configuração do Axon.
 *
 * <h2>O filtro por pacote</h2>
 * Só beans sob {@value #APPLICATION_PACKAGE} entram na varredura. Uma aplicação Quarkus tem centenas de
 * beans de plataforma, e nenhum deles vai ter um {@code @CommandHandler}: restringir é mais rápido e,
 * principalmente, torna o resultado previsível.
 */
public final class AxonHandlerLookup {

    /** Raiz dos pacotes desta aplicação; o que está fora não é varrido. */
    static final String APPLICATION_PACKAGE = "dev.manuelantunes.axonposts";

    /**
     * Nome do processor usado quando um event handler não declara {@link Namespace} em lugar nenhum.
     * <p>
     * Existe como rede: um handler sem namespace continua funcionando, num processor próprio, em vez de
     * ser silenciosamente ignorado.
     */
    static final String DEFAULT_PROCESSOR = "default-projection";

    private final BeanManager beanManager;

    public AxonHandlerLookup(BeanManager beanManager) {
        this.beanManager = beanManager;
    }

    /** Classes com pelo menos um {@code @CommandHandler}, agrupadas por pacote. */
    public Map<String, List<Class<?>>> commandHandlingComponents() {
        return byPackage(handlingComponents(CommandHandler.class));
    }

    /** Classes com pelo menos um {@code @QueryHandler}, agrupadas por pacote. */
    public Map<String, List<Class<?>>> queryHandlingComponents() {
        return byPackage(handlingComponents(QueryHandler.class));
    }

    /**
     * Classes com pelo menos um {@code @EventHandler}, agrupadas pelo <b>nome do processor</b>.
     * <p>
     * O nome sai do {@link Namespace} — no tipo, na classe envolvente ou no {@code package-info.java},
     * nessa ordem, que é a mesma que o Axon usa para resolver a anotação. É o que mantém a regra do
     * projeto Spring valendo aqui: o {@code @Namespace} está no {@code package-info} de
     * {@code application.post.event}, então <b>handler novo naquele pacote entra no processor sem tocar
     * em configuração</b>.
     */
    public Map<String, List<Class<?>>> eventHandlingComponents() {
        Map<String, List<Class<?>>> byProcessor = new TreeMap<>();
        for (Class<?> type : handlingComponents(EventHandler.class)) {
            byProcessor.computeIfAbsent(processorOf(type), name -> new ArrayList<>()).add(type);
        }
        byProcessor.values().forEach(types -> types.sort(Comparator.comparing(Class::getName)));
        return byProcessor;
    }

    private List<Class<?>> handlingComponents(Class<? extends Annotation> handlerAnnotation) {
        return beanManager.getBeans(Object.class, Any.INSTANCE).stream()
                .map(Bean::getBeanClass)
                .filter(type -> type.getName().startsWith(APPLICATION_PACKAGE))
                .filter(type -> hasHandlerMethod(type, handlerAnnotation))
                .distinct()
                .sorted(Comparator.comparing(Class::getName))
                .<Class<?>>map(type -> type)
                .toList();
    }

    /**
     * Procura o método anotado na classe e nas superclasses. {@code getMethods()} não serve: ele não
     * enxerga método público de classe não-pública nem method de pacote, e um handler assim existiria
     * sem nunca ser encontrado.
     */
    private static boolean hasHandlerMethod(Class<?> type, Class<? extends Annotation> handlerAnnotation) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isAnnotationPresent(handlerAnnotation)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@link Namespace} no tipo, na classe envolvente ou no pacote — a ordem em que o Axon a resolve. */
    private static String processorOf(Class<?> type) {
        return namespaceOn(type)
                .or(() -> enclosingNamespace(type))
                .or(() -> Optional.ofNullable(type.getPackage())
                        .map(pkg -> pkg.getAnnotation(Namespace.class))
                        .map(Namespace::value))
                .filter(value -> !value.isBlank())
                .orElse(DEFAULT_PROCESSOR);
    }

    private static Optional<String> namespaceOn(Class<?> type) {
        return Optional.ofNullable(type.getAnnotation(Namespace.class)).map(Namespace::value);
    }

    private static Optional<String> enclosingNamespace(Class<?> type) {
        Class<?> enclosing = type.getEnclosingClass();
        return enclosing == null ? Optional.empty() : namespaceOn(enclosing);
    }

    /**
     * Agrupa por pacote, como o lookup do Spring faz: um módulo de command handling por pacote, em vez de
     * um módulo gigante. O nome do módulo aparece em log e em diagnóstico, e assim ele diz de onde os
     * handlers vieram.
     */
    private static Map<String, List<Class<?>>> byPackage(List<Class<?>> types) {
        Map<String, List<Class<?>>> grouped = new LinkedHashMap<>();
        for (Class<?> type : types) {
            String packageName = type.getPackageName();
            grouped.computeIfAbsent(packageName, name -> new ArrayList<>()).add(type);
        }
        return grouped;
    }

    /**
     * {@code @Any} como literal. O {@code AnnotationLiteral} do CDI existe justamente para passar uma
     * anotação onde a API pede uma instância dela.
     */
    private static final class Any extends jakarta.enterprise.util.AnnotationLiteral<jakarta.enterprise.inject.Any>
            implements jakarta.enterprise.inject.Any {

        static final Any INSTANCE = new Any();
    }
}
