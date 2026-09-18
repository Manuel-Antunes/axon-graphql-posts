package dev.manuelantunes.axonposts.nativesupport;

/**
 * Marcador. Existe para que o módulo de runtime produza um jar com classes, que é o que o
 * {@code quarkus-extension-maven-plugin} precisa para escrever o descritor da extensão.
 * <p>
 * Todo o trabalho deste suporte é em <b>build time</b> e vive no módulo de deployment, em
 * {@code AxonNativeImageProcessor}. Não há nada a fazer em runtime: o que falta ao Axon sob GraalVM é
 * metadado de reflexão e registro de provedores de serviço, e as duas coisas se decidem ao compilar.
 */
public final class AxonNativeSupport {

    private AxonNativeSupport() {
    }
}
