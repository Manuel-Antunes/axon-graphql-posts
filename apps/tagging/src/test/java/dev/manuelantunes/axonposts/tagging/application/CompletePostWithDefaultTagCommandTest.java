package dev.manuelantunes.axonposts.tagging.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent.AssignedTag;
import dev.manuelantunes.axonposts.domain.post.event.PostPreCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.tagging.application.CompletePostWithDefaultTagCommand.CompletePostWithDefaultTag;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Given-when-then da ÚNICA decisão deste serviço.
 *
 * <h2>O que este arquivo prova, e por que ele é o teste mais importante daqui</h2>
 * O serviço de tagueamento não tem read model, não tem endpoint e não tem agregado próprio. Tudo o que
 * ele faz cabe num command handler — então é aqui que a regra dele é verificável sem broker, sem banco
 * e sem Quarkus. É o mesmo formato dos {@code *CommandTest} do outro app, de propósito: o
 * {@code AxonTestFixture} reidrata o {@code Post} a partir dos eventos dados, e a asserção é sobre o
 * evento que SAIU — que é o contrato com o resto da saga.
 */
class CompletePostWithDefaultTagCommandTest {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final PostId POST_ID = PostId.of("11111111-1111-1111-1111-111111111111");
    private static final UserId AUTHOR_ID = UserId.of("author-1");
    private static final Instant BORN_AT = Instant.parse("2026-09-01T11:59:00Z");

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create()
                .registerEntity(EventSourcedEntityModule.autodetected(PostId.class, Post.class))
                .registerCommandHandlingModule(
                        CommandHandlingModule.named("tag-decision")
                                .commandHandlers()
                                .autodetectedCommandHandlingComponent(
                                        config -> new CompletePostWithDefaultTagCommand(FIXED_CLOCK))
                );
        fixture = AxonTestFixture.with(configurer);
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    /** O evento que este serviço recebe do outro: o post existe, mas ainda não está completo. */
    private static PostPreCreatedEvent preCreated() {
        return new PostPreCreatedEvent(POST_ID, "Saga coreografada", "conteúdo", AUTHOR_ID, BORN_AT);
    }

    @Test
    void completesAPreCreatedPostWithTheDefaultTag() {
        fixture.given()
                .event(preCreated())
                .when()
                .command(new CompletePostWithDefaultTag(POST_ID))
                .then()
                .success()
                .events(new PostCreatedEvent(
                        POST_ID, "Saga coreografada", "conteúdo", AUTHOR_ID,
                        List.of(new AssignedTag(Tag.DEFAULT_ID.value(), Tag.DEFAULT_NAME)),
                        2L, NOW));
    }

    /**
     * A TERCEIRA GUARDA da coreografia, e a única que sobrevive a um inbox limpo.
     * <p>
     * A marca de origem descarta o eco e o inbox descarta a reentrega — mas as duas são infraestrutura,
     * e um inbox truncado as desarma. Esta guarda é do AGREGADO: {@code Post.complete} lança se o post
     * já estiver completo, e é por isso que o handler pergunta {@code isComplete()} antes. Sem ela, uma
     * entrega duplicada do broker — que é normal — viraria falha.
     */
    @Test
    void aSecondDeliveryDecidesNothingAndFailsNothing() {
        fixture.given()
                .event(preCreated())
                .event(new PostCreatedEvent(
                        POST_ID, "Saga coreografada", "conteúdo", AUTHOR_ID,
                        List.of(new AssignedTag(Tag.DEFAULT_ID.value(), Tag.DEFAULT_NAME)),
                        2L, NOW))
                .when()
                .command(new CompletePostWithDefaultTag(POST_ID))
                .then()
                .success()
                .noEvents();
    }

    /**
     * A tag padrão é do DOMÍNIO, e este teste é o que trava isso.
     * <p>
     * DOIS lugares atribuem a tag padrão — este serviço em produção e o dublê em processo na suíte do
     * outro app — e os dois têm de chegar ao MESMO id, senão a projeção do outro lado cria uma segunda
     * linha "Untagged" a cada post. Com a regra em {@code Tag.DEFAULT_ID} isso é consequência; com uma
     * constante local, seria coincidência mantida à mão.
     * <p>
     * O id é função pura do nome ({@code UUID.nameUUIDFromBytes("tag:" + DEFAULT_NAME)}), então a
     * asserção abaixo falha se alguém trocar o nome sem pensar no id — que é exatamente o acidente que
     * ela existe para pegar.
     */
    @Test
    void theDefaultTagIdentityComesFromTheDomainAndNotFromThisService() {
        fixture.given()
                .event(preCreated())
                .when()
                .command(new CompletePostWithDefaultTag(POST_ID))
                .then()
                .success()
                .eventsSatisfy(events -> {
                    PostCreatedEvent created = (PostCreatedEvent) events.getFirst().payload();
                    assertThat(created.tags())
                            .singleElement()
                            .satisfies(tag -> {
                                assertThat(tag.name()).isEqualTo(Tag.DEFAULT_NAME);
                                assertThat(tag.tagId()).isEqualTo(Tag.DEFAULT_ID.value());
                            });
                });
    }

    /**
     * A VERSÃO é o que o outro serviço observa para saber que a saga fechou.
     * <p>
     * O post nasce na 1 e chega à 2 — e é esse número que a subscription {@code onPostCreated} do
     * {@code posts-api} espera. Um evento que saísse na versão errada não quebraria nada aqui e faria o
     * assinante do outro lado esperar para sempre.
     */
    @Test
    void thePostReachesVersionTwo() {
        fixture.given()
                .event(preCreated())
                .when()
                .command(new CompletePostWithDefaultTag(POST_ID))
                .then()
                .success()
                .eventsSatisfy(events -> assertThat(((PostCreatedEvent) events.getFirst().payload()).version())
                        .isEqualTo(2L));
    }
}
