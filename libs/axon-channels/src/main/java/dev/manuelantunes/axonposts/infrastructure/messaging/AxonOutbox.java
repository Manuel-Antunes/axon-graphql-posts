package dev.manuelantunes.axonposts.infrastructure.messaging;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;

/**
 * <b>Marca um {@code Emitter} como outbox do Axon: qual canal é, e que namespaces saem por ele.</b>
 *
 * <pre>
 * &#64;Produces
 * &#64;Singleton
 * &#64;AxonOutbox(channel = CHANNEL, namespaces = "posts")
 * Emitter&lt;AxonEventEnvelope&gt; postEvents(&#64;Channel(CHANNEL) Emitter&lt;AxonEventEnvelope&gt; channel) {
 *     return channel;
 * }
 * </pre>
 *
 * Uma declaração: o que sai ao lado de para onde vai. Substituiu uma {@code interface AxonOutbox} com
 * três métodos, implementada por um bean em cada serviço, para declarar esses mesmos dois fatos.
 *
 * <h2>Por que num PRODUTOR, e não no campo injetado</h2>
 * Foi a primeira tentativa, e o CDI a recusa. Este é um qualifier; pôr um qualifier num ponto de injeção
 * exige um bean que tenha <b>todos</b> os qualifiers dali. O emitter de {@code @Channel} é um bean
 * sintético que o Quarkus gera com o qualifier {@code @Channel} e mais nada, então
 * {@code @Inject @Channel("x") @AxonOutbox(…) Emitter<…>} não resolve: nenhum bean atende.
 * <p>
 * E não adianta a lib oferecer esse bean: um produtor que casasse com qualquer canal teria de declarar
 * {@code @Channel} com {@code value()} {@code @Nonbinding}, e {@code @Channel.value()} é <b>binding</b>
 * — é ele que distingue um canal do outro.
 * <p>
 * No produtor a colisão some: o {@code @Channel} fica sozinho no parâmetro, onde o Quarkus o enxerga em
 * build time — e é essa visão que faz o emitter <b>existir</b>, porque canal sem {@code @Channel}
 * declarado é canal ligado ao broker sem por onde receber.
 *
 * <h2>Por que o nome do canal aparece DUAS vezes, e o que impede que divirjam</h2>
 * Porque não há de onde lê-lo uma vez só. O caminho natural seria o {@code @Channel} do parâmetro do
 * produtor, via {@code Bean#getInjectionPoints()} — e o <b>ArC devolve essa coleção vazia</b> para
 * produtores (medido: {@code injectionPoints=[]}). É limitação do contêiner, não escolha de desenho.
 * <p>
 * Então {@link OutboxRouting} confere: o emitter que o produtor devolve tem de ser <b>o mesmo objeto</b>
 * que o {@code ChannelRegistry} tem registrado sob {@link #channel()}. Se alguém copiar o arquivo e
 * trocar só um dos dois nomes, a resolução cai com os dois lados no erro — em vez de publicar no canal
 * errado em silêncio, que é o que uma duplicação sem guarda custaria.
 *
 * <h2>Por que os membros são {@code @Nonbinding}</h2>
 * Porque a lib precisa coletar <b>todos</b> os outboxes com um ponto de injeção só. Fossem binding, cada
 * combinação de canal e namespaces seria um qualifier diferente e a lib teria de saber de antemão quais
 * existem — que é exatamente o que ela não pode saber. Os valores continuam legíveis: vêm do
 * {@code Bean#getQualifiers()}, que o ArC materializa em bytecode (nada de reflexão, e portanto nada a
 * registrar para o native).
 */
@Qualifier
@Retention(RUNTIME)
@Target({METHOD, FIELD, PARAMETER, TYPE})
public @interface AxonOutbox {

    /**
     * O nome do canal, <b>igual</b> ao do {@code @Channel} do parâmetro e ao do
     * {@code mp.messaging.outgoing.&lt;canal&gt;}. É por ele que {@link OutboxRouting} acha o conector,
     * e é ele que a conferência contra o {@code ChannelRegistry} usa.
     * <p>
     * O default vazio existe só para a lib poder escrever {@code @AxonOutbox} sem membros no ponto de
     * injeção que coleta os outboxes; um produtor que o deixe assim falha na resolução da tabela.
     */
    @Nonbinding
    String channel() default "";

    /**
     * Os {@code namespace} do {@code @Event} que saem por este outbox — {@code "posts"},
     * {@code "users"}…
     *
     * <h3>Por que namespace, e não uma lista de eventos em configuração</h3>
     * Houve uma propriedade ({@code axonposts.messaging.outbox.&lt;canal&gt;.events}, globs sobre
     * {@code namespace.Name}) escrita para espelhar o {@code routing-keys} da entrada. A simetria era
     * aparente: na entrada o seletor anda junto com nome de fila e binding, que mudam por ambiente; na
     * saída não muda por ambiente nunca. <b>O que um serviço publica é contrato dele</b> — o código diz
     * o quê, a configuração diz para onde.
     * <p>
     * O que a granularidade de namespace custa: não dá para mandar {@code posts.PostCreated} a um
     * destino e {@code posts.PostUpdated} a outro. O caso que parece exigir isso — tudo num barramento
     * de auditoria e só os posts no broker — continua exprimível, porque um evento que casa com vários
     * outboxes sai em todos.
     */
    @Nonbinding
    String[] namespaces() default {};

    /**
     * O literal que a lib usa para coletar os outboxes. Com os membros {@code @Nonbinding}, ele casa com
     * qualquer {@code @AxonOutbox}, quaisquer que sejam o canal e os namespaces.
     */
    final class Literal extends AnnotationLiteral<AxonOutbox> implements AxonOutbox {

        public static final Literal ANY = new Literal();

        private static final long serialVersionUID = 1L;

        @Override
        public String channel() {
            return "";
        }

        @Override
        public String[] namespaces() {
            return new String[0];
        }
    }
}
