package dev.manuelantunes.axonposts.testing;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevServicesSchemaVersionTest {
    private static String migration(String version) {
        return "db/migration/V" + version + "__qualquer_coisa.sql";
    }

    @Test
    void readsASingleNumberVersion() {
        assertThat(DevServicesSchema.versionOf(migration("7"))).isEqualTo(List.of(7));
    }

    @Test
    void underscoreAndDotAreTheSameSeparator() {
        assertThat(DevServicesSchema.versionOf(migration("1_0_1")))
                .isEqualTo(DevServicesSchema.versionOf(migration("1.0.1")))
                .isEqualTo(List.of(1, 0, 1));
    }

    @Test
    void tenComesAfterTwo() {
        assertThat(DevServicesSchema.VERSION_ORDER.compare(migration("10"), migration("2")))
                .isPositive();
    }

    @Test
    void oneDotTenComesAfterOneDotTwo() {
        assertThat(DevServicesSchema.VERSION_ORDER.compare(migration("1_10"), migration("1_2")))
                .isPositive();
    }

    @Test
    void aShorterVersionThatIsAPrefixComesFirst() {
        assertThat(DevServicesSchema.VERSION_ORDER.compare(migration("1_0"), migration("1_0_1")))
                .isNegative();
    }

    @Test
    void theSameVersionWrittenTwoWaysIsEqual() {
        assertThat(DevServicesSchema.VERSION_ORDER.compare(migration("2_1"), migration("2.1")))
                .isZero();
    }

    @Test
    void sortsAWholeListTheWayFlywayWouldApplyIt() {
        List<String> shuffled = List.of(
                migration("10"), migration("2"), migration("1_0_1"), migration("1"), migration("1_0"));

        assertThat(shuffled.stream().sorted(DevServicesSchema.VERSION_ORDER).toList())
                .containsExactly(
                        migration("1"), migration("1_0"), migration("1_0_1"),
                        migration("2"), migration("10"));
    }
}
