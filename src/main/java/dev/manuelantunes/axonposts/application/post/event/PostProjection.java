package dev.manuelantunes.axonposts.application.post.event;

/**
 * Identidade do event processor que executa os event handlers deste pacote.
 * <p>
 * O nome aparece em dois lugares que precisam combinar, e por isso é uma constante e não um literal:
 * no {@code @Namespace} do {@code package-info.java} daqui (que marca <i>quais</i> handlers pertencem ao
 * processor) e no {@code EventProcessorDefinition} do {@code AxonConfig} (que diz <i>como</i> ele roda).
 * Se os dois divergirem, os handlers caem num processor pooled default e a projeção vira assíncrona.
 */
public final class PostProjection {

    /** Nome do event processor dos handlers de evento de Post. */
    public static final String PROCESSOR = "post-projection";

    private PostProjection() {
    }
}
