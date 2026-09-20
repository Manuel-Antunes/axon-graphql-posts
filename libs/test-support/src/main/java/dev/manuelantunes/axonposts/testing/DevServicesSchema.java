package dev.manuelantunes.axonposts.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * As DUAS listas que têm de ser a mesma: o que o Dev Services executa e o que existe em
 * {@code db/migration}.
 *
 * <h2>Por que isto existe</h2>
 * `quarkus.datasource.devservices.init-script-path` aceita uma LISTA, e ela aponta direto para as
 * migrations. Antes apontava para um `db/init/schema.sql` que o `maven-antrun-plugin` concatenava na
 * fase `process-resources` — e aquilo violava o <b>R</b> de REPEATABLE do FIRST: o resultado do teste
 * dependia de uma FASE DO MAVEN ter rodado. Quem rodasse a suíte pela IDE, que copia recursos mas não
 * executa o antrun, via
 *
 * <pre>
 * ContainerLaunchException: Could not load classpath init script: db/init/schema.sql
 * </pre>
 *
 * uma mensagem sobre container para um defeito que não tinha nada a ver com container.
 *
 * <h2>O que ele mede mora AQUI, e as asserções ficam em cada app</h2>
 * Os DOIS serviços têm event store e migrations próprias, então os dois precisam do mesmo guarda. A
 * lógica é uma; o que muda é a lista de cada um — e a lista é o que o teste afirma.
 */
public final class DevServicesSchema {

    /** A pasta que o Flyway lê inteira, e de onde a lista de scripts tem de sair. */
    public static final String MIGRATIONS = "db/migration";

    public static final String PROPERTY = "quarkus.datasource.devservices.init-script-path";

    private DevServicesSchema() {
    }

    /** O que o `application.properties` manda executar, NA ORDEM em que o container executa. */
    public static List<String> configured() {
        return ConfigProvider.getConfig().getValues(PROPERTY, String.class).stream()
                .map(String::trim)
                .toList();
    }

    /**
     * As migrations que existem DE VERDADE no classpath, ordenadas por VERSÃO e não por nome.
     * <p>
     * A distinção não é preciosismo: em ordem lexicográfica `V10__x.sql` viria antes de `V2__x.sql`, e
     * o container subiria com um schema que nunca existiu em produção.
     */
    public static List<String> onClasspath() {
        URL directory = Thread.currentThread().getContextClassLoader().getResource(MIGRATIONS);
        if (directory == null) {
            throw new IllegalStateException(MIGRATIONS + " não está no classpath");
        }
        try (Stream<Path> files = Files.list(Path.of(directory.getPath()))) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
                    .sorted(VERSION_ORDER)
                    .map(name -> MIGRATIONS + "/" + name)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível listar " + MIGRATIONS, e);
        }
    }

    /**
     * A VERSÃO de uma migration, no formato do Flyway: {@code V<versão>__<descrição>.sql}.
     *
     * <h2>Por que uma LISTA e não um int</h2>
     * A convenção que o próprio Flyway recomenda é {@code Prefixo + Versão + Separador + Descrição},
     * e a versão é composta: {@code V1_0_1__cria_tabela.sql}. O Flyway trata `_` e `.` como o MESMO
     * separador, então {@code V1_0_1} e {@code V1.0.1} são a mesma versão — 1.0.1.
     * <p>
     * A primeira versão disto fazia {@code Integer.parseInt} do trecho inteiro e explodia com
     * {@code NumberFormatException} em qualquer migration que seguisse a recomendação. As migrations
     * deste projeto usam versão de uma parte só ({@code V1__}, {@code V2__}), então o defeito estava
     * dormindo: ele apareceria no dia em que alguém escrevesse a primeira {@code V7_1__}.
     */
    public static List<Integer> versionOf(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String version = fileName.substring(1, fileName.indexOf("__"));
        return Arrays.stream(version.split("[._]")).map(Integer::parseInt).toList();
    }

    /**
     * A ordem em que o Flyway aplicaria, e portanto a ordem em que o container tem de executar.
     *
     * <h2>Duas coisas que a ordem alfabética erra</h2>
     * {@code V10} viria antes de {@code V2}, e {@code V1_10} antes de {@code V1_2}. As duas produzem
     * um container com um schema que nunca existiu em produção — e nenhuma delas falha na hora: falha
     * depois, num `ALTER TABLE` contra uma tabela que ainda não foi criada.
     * <p>
     * Parte a parte, e a mais CURTA vence quando é prefixo da outra: 1.0 vem antes de 1.0.1, que é o
     * que o Flyway faz.
     */
    public static final Comparator<String> VERSION_ORDER = (left, right) -> {
        List<Integer> a = versionOf(left);
        List<Integer> b = versionOf(right);
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int comparison = Integer.compare(a.get(i), b.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(a.size(), b.size());
    };

    /** Verdadeiro quando o caminho configurado resolve como recurso — o defeito original. */
    public static boolean resolvesOnClasspath(String script) {
        return Thread.currentThread().getContextClassLoader().getResource(script) != null;
    }
}
