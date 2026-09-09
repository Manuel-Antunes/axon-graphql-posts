package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.PasswordHash;

/**
 * <b>Mixin</b> de autenticação: quem implementa vira uma identidade que se prova por credencial.
 *
 * <h2>O contrato, em duas metades</h2>
 * <ul>
 *   <li><b>o que você implementa</b>: {@link #email()} e {@link #passwordHash()} — as duas propriedades
 *       que formam a credencial;</li>
 *   <li><b>o que você ganha</b>: {@link #authenticates} e {@link #identifiedBy}, com as regras dentro.</li>
 * </ul>
 *
 * <h2>Por que tirar isto de dentro de {@code User}</h2>
 * {@code User} tem duas responsabilidades que só coincidem por acidente de modelagem: <b>ser alguém</b>
 * (id, nome, papel, hierarquia com {@code Author}) e <b>provar que é</b>. A segunda não é sobre usuários
 * — é sobre credenciais, e valeria igual para um cliente de API ou um serviço com chave própria.
 * <p>
 * Separadas, cada uma muda pela sua razão: acrescentar um tipo de usuário mexe em {@code User}, trocar a
 * política de senha mexe aqui. Enquanto estavam na mesma classe, os dois motivos apontavam para o mesmo
 * arquivo.
 *
 * <h2>O algoritmo continua de fora</h2>
 * {@link #authenticates} recebe um {@link PasswordVerifier} em vez de conhecer BCrypt. O mixin sabe as
 * <i>regras</i> — senha vazia nunca autentica, a comparação é contra o hash guardado — e delega a
 * <i>criptografia</i>. É o que mantém o domínio sem import de framework.
 */
public interface Authenticatable {

    /** A identidade pública da credencial: por onde se faz login. */
    Email email();

    /** O segredo, já derivado. Nunca a senha em claro — ver {@link PasswordHash}. */
    PasswordHash passwordHash();

    /**
     * A senha em claro corresponde ao hash guardado?
     * <p>
     * A guarda contra vazio vem antes do verifier de propósito: um {@code PasswordEncoder} chamado com
     * {@code null} lança, e com {@code ""} gasta um BCrypt inteiro para dizer não.
     */
    default boolean authenticates(String rawPassword, PasswordVerifier verifier) {
        return rawPassword != null
                && !rawPassword.isBlank()
                && verifier.matches(rawPassword, passwordHash().value());
    }

    /**
     * Esta é a credencial deste e-mail? O {@link Email} já normaliza para minúsculas no construtor, então
     * a comparação é exata — e é aqui que se veria se um dia deixasse de ser.
     */
    default boolean identifiedBy(Email candidate) {
        return candidate != null && email().equals(candidate);
    }
}
