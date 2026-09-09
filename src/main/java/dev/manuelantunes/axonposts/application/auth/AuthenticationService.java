package dev.manuelantunes.axonposts.application.auth;

import dev.manuelantunes.axonposts.domain.user.PasswordVerifier;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.InvalidCredentialsException;
import dev.manuelantunes.axonposts.infrastructure.security.JwtIssuer;
import org.springframework.stereotype.Service;

/**
 * Login: e-mail e senha entram, token sai.
 *
 * <h2>Por que não é um command do Axon</h2>
 * Os commands deste projeto existem porque produzem <b>eventos</b> — fatos que ficam gravados e de que
 * outros reagem. Autenticar não produz fato nenhum: é uma pergunta sobre o estado corrente. Passar isso
 * pelo command bus só acrescentaria cerimônia, e um {@code UserLoggedIn} no event store seria um evento
 * que ninguém consome e que ninguém pode reidratar em nada.
 *
 * <h2>Um erro só para os dois jeitos de falhar</h2>
 * E-mail que não existe e senha errada devolvem a <b>mesma</b> exceção, sem dizer qual foi. Distinguir
 * os dois transforma o login num oráculo de quais e-mails têm conta.
 * <p>
 * Um e-mail malformado também cai aqui e não vira {@code BAD_REQUEST}: para quem tenta adivinhar, "esse
 * e-mail é inválido" e "esse e-mail não tem conta" são a mesma informação útil.
 */
@Service
public class AuthenticationService {

    private final UserRepository users;
    private final PasswordVerifier passwordVerifier;
    private final JwtIssuer jwtIssuer;

    public AuthenticationService(UserRepository users, PasswordVerifier passwordVerifier, JwtIssuer jwtIssuer) {
        this.users = users;
        this.passwordVerifier = passwordVerifier;
        this.jwtIssuer = jwtIssuer;
    }

    /** O usuário autenticado e o token dele. */
    public record Session(User user, JwtIssuer.IssuedToken token) {
    }

    public Session login(String email, String rawPassword) {
        User user = findByEmail(email).orElseThrow(InvalidCredentialsException::new);

        if (!user.authenticates(rawPassword, passwordVerifier)) {
            throw new InvalidCredentialsException();
        }

        return new Session(user, jwtIssuer.issue(user));
    }

    /** Um e-mail inválido não é erro de formulário aqui — é só mais uma credencial que não confere. */
    private java.util.Optional<User> findByEmail(String email) {
        try {
            return users.findByEmail(Email.of(email));
        } catch (RuntimeException malformed) {
            return java.util.Optional.empty();
        }
    }
}
