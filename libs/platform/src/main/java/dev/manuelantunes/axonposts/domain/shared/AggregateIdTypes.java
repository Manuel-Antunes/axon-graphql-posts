package dev.manuelantunes.axonposts.domain.shared;

import java.util.Map;

/**
 * A costura de composição do monólito modular: cada módulo declara o tipo do id dos <b>seus</b>
 * agregados, e a raiz de composição junta.
 *
 * <h2>Por que isto existe</h2>
 * O Axon precisa saber o tipo do id de cada entidade, e a extensão de Quarkus pede <b>um</b>
 * {@code EventSourcedEntityConfigurer}. Um único bean com o mapa de todos os agregados — que é como o
 * projeto começou — obriga esse bean a conhecer {@code Post}, {@code Tag} e {@code User} ao mesmo
 * tempo. Numa lib folha isso seria ciclo: a base passaria a depender de cada módulo de domínio.
 * <p>
 * Com esta porta, {@code libs/posts} declara {@code Post} e {@code Tag}, {@code libs/users} declara
 * {@code User}, e nenhum módulo conhece o outro. A aplicação — que já conhece todos, porque é ela que
 * os compõe — coleta as implementações e monta o mapa.
 *
 * <h2>O que isto muda na regra do projeto</h2>
 * "Entidade nova = uma linha no mapa" continua valendo, mas a linha passa a ser <b>no mapa do próprio
 * módulo</b>. É a diferença entre um ponto central que todo mundo edita e uma declaração local — e é o
 * que permite acrescentar um agregado sem tocar em nada fora da lib dele.
 */
public interface AggregateIdTypes {

    /** Entidade → tipo do id. O Axon recebe isto em {@code EventSourcedEntityModule.autodetected}. */
    Map<Class<?>, Class<?>> idTypes();
}
