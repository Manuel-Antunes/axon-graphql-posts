package dev.manuelantunes.axonposts.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Este teste precisa do ARTEFATO EMPACOTADO, e diz isso em vez de estourar no framework.
 *
 * <h2>O que ele conserta</h2>
 * Um {@code @QuarkusIntegrationTest} é caixa-preta: ele não sobe a aplicação em processo, ele executa
 * o BINÁRIO que o {@code package} produziu. Rodado sem esse binário — clicando a classe na IDE, por
 * exemplo — a falha era esta:
 *
 * <pre>
 * IllegalStateException: Unable to locate the artifact metadata file created that must be
 * created by Quarkus in order to run integration tests.
 * </pre>
 *
 * Tecnicamente correta e praticamente inútil: ela não diz QUAL comando produz o artefato, e aparece
 * como ERRO — o que sugere defeito no código, quando o código não foi nem executado.
 *
 * <h2>Por que SALTAR e não falhar</h2>
 * Porque não há o que afirmar. Um teste que não tem sujeito não está falhando, está fora de contexto —
 * e `disabled` com a razão escrita é a resposta honesta do JUnit para isso. A condição é avaliada
 * ANTES do {@code beforeAll} da extensão do Quarkus, então a tentativa de boot nem acontece.
 *
 * <h2>E por que isto NÃO esconde uma regressão</h2>
 * Porque no fluxo que importa o artefato SEMPRE existe: quem roda estes testes é o failsafe, na fase
 * {@code integration-test}, que vem depois do {@code package} — e só com o perfil {@code native}, que
 * é o que vira o {@code skipITs} para {@code false}. Lá a condição nunca salta. Ela só fala com quem
 * rodou a classe fora do fluxo dela.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresNativeArtifact.OnlyWhenTheArtifactExists.class)
public @interface RequiresNativeArtifact {

    class OnlyWhenTheArtifactExists implements ExecutionCondition {

        /** O que o `package` escreve e o que o `IntegrationTestUtil` do Quarkus procura. */
        private static final Path ARTIFACT = Path.of("target", "quarkus-artifact.properties");

        private static final String HOW_TO_BUILD_IT =
                "este teste roda contra o BINÁRIO NATIVO e não há um: construa com "
                        + "`./mvnw verify -Dnative -Pnative-clt-toolchain -pl apps/posts-api -am`, "
                        + "que empacota e roda os *IT pelo failsafe. Procurado em " + ARTIFACT.toAbsolutePath();

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            return Files.isReadable(ARTIFACT)
                    ? ConditionEvaluationResult.enabled("o artefato empacotado está em " + ARTIFACT)
                    : ConditionEvaluationResult.disabled(HOW_TO_BUILD_IT);
        }
    }
}
