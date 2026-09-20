package dev.manuelantunes.axonposts.testing;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A ORDEM das migrations, e os casos que a ordem alfabética erra.
 *
 * <h2>Por que este arquivo existe</h2>
 * A primeira versão de {@link DevServicesSchema#versionOf} fazia {@code Integer.parseInt} do trecho
 * inteiro da versão. Funcionava com as migrations deste projeto — todas de uma parte só ({@code V1__},
 * {@code V2__}) — e explodiria com {@code NumberFormatException} na primeira que seguisse a convenção
 * que o próprio Flyway recomenda, {@code Prefixo + Versão + Separador + Descrição}
 * ({@code V1_0_1__cria_tabela.sql}).
 * <p>
 * O defeito estava DORMINDO: nenhum teste o pegaria até alguém escrever aquela migration, e aí a falha
 * seria numa ferramenta de teste, não no código de produção — o pior lugar para descobrir. Os casos
 * abaixo são os que ninguém escreve hoje e que alguém vai escrever.
 */
class DevServicesSchemaVersionTest {

    private static String migration(String version) {
        return "db/migration/V" + version + "__qualquer_coisa.sql";
    }

    @Test
    void readsASingleNumberVersion() {
        assertThat(DevServicesSchema.versionOf(migration("7"))).isEqualTo(List.of(7));
    }

    /** O `_` e o `.` são o MESMO separador para o Flyway: as duas formas são a versão 1.0.1. */
    @Test
    void underscoreAndDotAreTheSameSeparator() {
        assertThat(DevServicesSchema.versionOf(migration("1_0_1")))
                .isEqualTo(DevServicesSchema.versionOf(migration("1.0.1")))
                .isEqualTo(List.of(1, 0, 1));
    }

    /**
     * O CASO CLÁSSICO: em ordem alfabética `V10` vem antes de `V2`, e o container subiria com um
     * schema que nunca existiu — falhando depois, num `ALTER TABLE` contra uma tabela inexistente.
     */
    @Test
    void tenComesAfterTwo() {
        assertThat(DevServicesSchema.VERSION_ORDER.compare(migration("10"), migration("2")))
                .isPositive();
    }

    /** O mesmo defeito uma casa adiante, que só aparece com a convenção recomendada. */
    @Test
    void oneDotTenComesAfterOneDotTwo() {
        assertThat(DevServicesSchema.VERSION_ORDER.compare(migration("1_10"), migration("1_2")))
                .isPositive();
    }

    /** A mais CURTA vem primeiro quando é prefixo da outra — é o que o Flyway faz. */
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
