package dev.manuelantunes.axonposts.infrastructure.messaging;

import org.eclipse.microprofile.reactive.messaging.Metadata;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * O conector em memória não tem endereçamento: quem consome um canal {@code smallrye-in-memory} é quem
 * o injeta, e não há exchange, tópico nem partição a escolher.
 *
 * <h2>Por que é um bean, e não um {@code ChannelAddressing.NONE}</h2>
 * Porque havia uma constante com esse nome, e uma constante não participa da resolução por conector: com
 * o endereçamento escolhido por {@code mp.messaging.outgoing.&lt;canal&gt;.connector}, um canal em memória sem
 * implementação registrada derrubaria o roteamento com "nenhum ChannelAddressing atende
 * smallrye-in-memory" — e o certo, neste caso, não é falhar, é não endereçar nada.
 * <p>
 * É o que permite exercitar o outbox inteiro — seleção, roteamento, envelope — num {@code @QuarkusTest}
 * com o {@code InMemoryConnector}, sem broker de pé.
 */
@ApplicationScoped
public class InMemoryAddressing implements ChannelAddressing {

    @Override
    public String connector() {
        return "smallrye-in-memory";
    }

    @Override
    public Metadata addressing(EventAddress address) {
        return Metadata.empty();
    }
}
