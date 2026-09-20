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

public final class DevServicesSchema {
    public static final String MIGRATIONS = "db/migration";

    public static final String PROPERTY = "quarkus.datasource.devservices.init-script-path";

    private DevServicesSchema() {
    }

    public static List<String> configured() {
        return ConfigProvider.getConfig().getValues(PROPERTY, String.class).stream()
                .map(String::trim)
                .toList();
    }

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

    public static List<Integer> versionOf(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String version = fileName.substring(1, fileName.indexOf("__"));
        return Arrays.stream(version.split("[._]")).map(Integer::parseInt).toList();
    }

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

    public static boolean resolvesOnClasspath(String script) {
        return Thread.currentThread().getContextClassLoader().getResource(script) != null;
    }
}
