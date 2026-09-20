package dev.manuelantunes.axonposts.infrastructure.persistence;

import dev.manuelantunes.axonposts.testing.DevServicesSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O schema que o Dev Services cria É a lista de migrations — e este teste a mantém honesta.
 *
 * <h2>Por que ele NÃO é `@QuarkusTest`</h2>
 * Porque o que ele afirma é sobre CONFIGURAÇÃO e CLASSPATH, e as duas coisas existem sem aplicação de
 * pé. Roda em milissegundos e sem Docker — que é o <b>F</b> do FIRST. Um guarda que só roda quando o
 * Docker está ligado é um guarda que não roda.
 *
 * <p>O porquê da lista (e o defeito que ela consertou) está em {@link DevServicesSchema}.
 */
class DevServicesSchemaTest {

    /**
     * <b>MIGRATION NOVA = MAIS UMA LINHA na propriedade.</b> Esquecer não quebra compilação e não
     * quebra o Flyway, que lê a pasta inteira: quebra o Dev Services, que criaria o container SEM
     * aquela tabela — e o `validate` do Hibernate derrubaria a aplicação com `missing table`, longe
     * da causa.
     */
    @Test
    void everyMigrationOnTheClasspathIsInTheInitScriptList() {
        assertThat(DevServicesSchema.configured())
                .as("cada migration de %s tem de estar em `%s`, na mesma ordem",
                        DevServicesSchema.MIGRATIONS, DevServicesSchema.PROPERTY)
                .isEqualTo(DevServicesSchema.onClasspath());
    }

    /**
     * A ordem importa porque as migrations dependem umas das outras. Executadas fora de ordem, o
     * container sobe com um schema que nunca existiu em produção.
     */
    @Test
    void theScriptsAreListedInAscendingVersionOrder() {
        assertThat(DevServicesSchema.configured())
                .isSortedAccordingTo(DevServicesSchema.VERSION_ORDER)
                .doesNotHaveDuplicates();
    }

    /**
     * Um caminho que não resolve é o defeito ORIGINAL, agora afirmado onde dói barato: aqui a falha
     * diz QUAL caminho não existe, em vez de dizer que um container não subiu.
     */
    @Test
    void everyConfiguredScriptResolvesOnTheClasspath() {
        assertThat(DevServicesSchema.configured())
                .isNotEmpty()
                .allSatisfy(script -> assertThat(DevServicesSchema.resolvesOnClasspath(script))
                        .as("`%s` está em `%s` mas não existe no classpath",
                                script, DevServicesSchema.PROPERTY)
                        .isTrue());
    }
}
