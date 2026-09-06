package dev.manuelantunes.axonposts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
public class AxonGraphqlPostsApplication {

    public static void main(String[] args) {
        ensureSqliteDirectory();
        SpringApplication.run(AxonGraphqlPostsApplication.class, args);
    }

    /** O driver SQLite cria o arquivo, mas não o diretório de ./data/posts.db. */
    private static void ensureSqliteDirectory() {
        try {
            Files.createDirectories(Path.of("data"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
