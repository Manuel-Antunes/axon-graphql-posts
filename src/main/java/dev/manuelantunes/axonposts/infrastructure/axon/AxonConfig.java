package dev.manuelantunes.axonposts.infrastructure.axon;

import dev.manuelantunes.axonposts.application.post.event.PostProjection;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Infraestrutura do Axon 5 para a POC. Tudo aqui é <b>escolha de deploy</b>, não de negócio: nenhuma
 * regra de Post aparece neste arquivo, e trocar qualquer um destes beans não muda uma linha de domínio
 * ou de aplicação.
 * <ul>
 *   <li><b>Event store em memória</b>: os eventos vivem só no processo (somem no restart); o SQLite
 *       guarda apenas o read model. Sem este bean o starter cairia no
 *       {@code AggregateBasedJpaEventStorageEngine} (JPA no mesmo SQLite) — os autoconfigs JPA do Axon
 *       estão excluídos no {@code application.yml} para não criar as tabelas dele. Trocar por
 *       JPA/Axon Server = remover o bean e a exclusão.</li>
 *   <li><b>Token store em memória</b>: só entra em jogo se o processor virar pooled streaming (o
 *       subscribing não usa token). Sem este bean o starter registraria um {@code JdbcTokenStore} no
 *       SQLite — o autoconfig JDBC também está excluído no {@code application.yml}.</li>
 *   <li><b>Sem Axon Server</b>: o connector não está no classpath, então o Axon usa
 *       {@code SimpleCommandBus} + {@code SimpleQueryBus} locais.</li>
 *   <li><b>Processor "post-projection" em modo subscribing</b>: o {@code subscribingMatching} seleciona
 *       os handlers cujo {@code @Namespace} bate com o nome — no caso, todos os event handlers do pacote
 *       {@code application.post.event}, que herdam o namespace do {@code package-info.java}. O default
 *       do Axon 5 é pooled streaming (assíncrono); é aqui que se troca para ver consistência
 *       eventual.</li>
 *   <li>{@link Clock} como bean: injetado nos commands para carimbar os eventos, e substituível
 *       por um clock fixo em teste.</li>
 * </ul>
 */
@Configuration
public class AxonConfig {

    @Bean
    public EventStorageEngine eventStorageEngine() {
        return new InMemoryEventStorageEngine();
    }

    @Bean
    public TokenStore tokenStore() {
        return new InMemoryTokenStore();
    }

    @Bean
    public EventProcessorDefinition postProjectionProcessor() {
        return EventProcessorDefinition
                .subscribingMatching(PostProjection.PROCESSOR)
                .notCustomized();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
