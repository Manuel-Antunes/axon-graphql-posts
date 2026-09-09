package dev.manuelantunes.axonposts.infrastructure.security;

import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Cria dois usuários na primeira subida, para a POC ter em quem logar: um {@code Author} e um
 * {@code Reader}. Só roda com a tabela vazia.
 * <p>
 * As senhas são fixas e estão no código <b>porque isto é uma POC de demonstração</b> — num sistema real
 * seria um cadastro, e este arquivo não existiria.
 */
@Component
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    public static final String AUTHOR_EMAIL = "manuel@example.com";
    public static final String READER_EMAIL = "leitor@example.com";
    public static final String DEMO_PASSWORD = "segredo123";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public DemoUserSeeder(UserRepository users, PasswordEncoder encoder, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!users.isEmpty()) {
            return;
        }

        Author author = Author.register(
                UserId.newId(), AUTHOR_EMAIL, "Manuel Antunes",
                encoder.encode(DEMO_PASSWORD), "Escrevendo sobre Axon e GraphQL.", clock.instant());

        User reader = User.register(
                UserId.newId(), READER_EMAIL, "Leitor Anônimo",
                encoder.encode(DEMO_PASSWORD), clock.instant());

        users.save(author);
        users.save(reader);

        log.info("usuários de demonstração criados: {} (autor) e {} (leitor), senha '{}'",
                AUTHOR_EMAIL, READER_EMAIL, DEMO_PASSWORD);
    }
}
