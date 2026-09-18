package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.user.vo.PasswordHash;

import java.util.Optional;


/**
 * <b>Mixin</b> de autenticação: quem implementa é uma credencial — algo que prova uma identidade.
 *
 * <h2>Ele mudou de classe, e essa foi a razão de ele existir</h2>
 * Até a migração para o Keycloak quem implementava era o {@code User}. Agora é a {@link Account}, e o
 * {@code User} não mudou <b>uma linha</b> por causa disso: ele nunca teve os métodos, só delegava ao
 * mixin. Se a lógica estivesse dentro da classe, mover credencial para uma tabela à parte seria
 * reescrever {@code User}, {@code Author} e tudo que os toca.
 * <p>
 * É o argumento inteiro a favor de separar por interface em vez de por herança: a responsabilidade
 * migrou de classe sem arrastar a hierarquia junto.
 *
 * <h2>O contrato</h2>
 * <ul>
 *   <li><b>você implementa</b>: {@link #provider()}, {@link #subject()} e {@link #passwordHash()};</li>
 *   <li><b>você ganha</b>: as perguntas que se fazem sobre uma credencial, já respondidas.</li>
 * </ul>
 *
 * <h2>Senha opcional</h2>
 * {@link #passwordHash()} devolve {@link Optional} porque no modelo do Keycloak <b>algumas contas têm
 * senha e outras não</b>: uma conta criada por login social nunca teve uma. Um {@code PasswordHash}
 * anulável obrigaria todo chamador a lembrar disso; o {@code Optional} não deixa esquecer.
 */
public interface Authenticatable {

    /** Quem garante esta identidade. */
    AuthProvider provider();

    /**
     * O identificador da conta <b>no provedor</b> — o {@code sub} do token, no caso do Keycloak.
     * <p>
     * Não é o e-mail: e-mail muda, e dois provedores podem afirmar o mesmo e-mail. O par
     * {@code (provider, subject)} é o que identifica uma credencial de forma estável.
     */
    String subject();

    /** Vazio quando a conta não tem senha local — o caso normal depois da migração. */
    Optional<PasswordHash> passwordHash();

    /** Dá para entrar com senha por esta conta? */
    default boolean hasPassword() {
        return passwordHash().isPresent();
    }

    /** A credencial mora fora daqui? */
    default boolean isFederated() {
        return provider().isFederated();
    }

    /**
     * A senha em claro corresponde ao hash guardado?
     * <p>
     * Uma conta federada devolve {@code false} sem consultar nada — não há hash, e não deveria haver:
     * quem verifica a credencial dela é o provedor.
     * <p>
     * A guarda contra vazio vem antes do verifier de propósito: um {@code PasswordEncoder} chamado com
     * {@code null} lança, e com {@code ""} gasta um BCrypt inteiro para dizer não.
     */
    default boolean authenticates(String rawPassword, PasswordVerifier verifier) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return false;
        }
        return passwordHash()
                .map(hash -> verifier.matches(rawPassword, hash.value()))
                .orElse(false);
    }

    /** Esta credencial é a do par {@code (provider, subject)} procurado? */
    default boolean identifies(AuthProvider provider, String subject) {
        return provider() == provider && subject().equals(subject);
    }
}
