package dev.manuelantunes.axonposts.infrastructure.persistence;

import dev.manuelantunes.axonposts.testing.DevServicesSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevServicesSchemaTest {
    @Test
    void everyMigrationOnTheClasspathIsInTheInitScriptList() {
        assertThat(DevServicesSchema.configured())
                .as("cada migration de %s tem de estar em `%s`, na mesma ordem",
                        DevServicesSchema.MIGRATIONS, DevServicesSchema.PROPERTY)
                .isEqualTo(DevServicesSchema.onClasspath());
    }

    @Test
    void theScriptsAreListedInAscendingVersionOrder() {
        assertThat(DevServicesSchema.configured())
                .isSortedAccordingTo(DevServicesSchema.VERSION_ORDER)
                .doesNotHaveDuplicates();
    }

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
