package dev.manuelantunes.axonposts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de entrada.
 * <p>
 * A criação do diretório {@code ./data} saiu junto com o SQLite: o banco agora é um serviço, não um
 * arquivo — {@code docker compose up -d} antes de subir a aplicação.
 */
@SpringBootApplication
public class AxonGraphqlPostsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AxonGraphqlPostsApplication.class, args);
    }
}
