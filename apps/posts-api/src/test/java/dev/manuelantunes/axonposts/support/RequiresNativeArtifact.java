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

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresNativeArtifact.OnlyWhenTheArtifactExists.class)
public @interface RequiresNativeArtifact {
    class OnlyWhenTheArtifactExists implements ExecutionCondition {
        private static final Path ARTIFACT = Path.of("target", "quarkus-artifact.properties");

        private static final String HOW_TO_BUILD_IT =
                "este teste roda contra o BINÁRIO NATIVO e não há um: construa com "
                        + "`./mvnw verify -Dnative.it -Pnative-clt-toolchain` a partir da RAIZ, "
                        + "que empacota e roda os *IT pelo failsafe. -Dnative sozinho produz o binário "
                        + "sem o arcabouço dos ITs, que é o que os alvos lambda-* precisam. "
                        + "Procurado em " + ARTIFACT.toAbsolutePath();

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            return Files.isReadable(ARTIFACT)
                    ? ConditionEvaluationResult.enabled("o artefato empacotado está em " + ARTIFACT)
                    : ConditionEvaluationResult.disabled(HOW_TO_BUILD_IT);
        }
    }
}
