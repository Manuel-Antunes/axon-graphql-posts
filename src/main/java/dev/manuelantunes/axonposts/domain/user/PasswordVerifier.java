package dev.manuelantunes.axonposts.domain.user;

/**
 * Porta de verificação de senha: o domínio pergunta "esta senha em claro corresponde a este hash?" sem
 * ficar sabendo qual algoritmo responde.
 * <p>
 * Existe para que {@link User#authenticates} seja um método de domínio de verdade e não uma comparação
 * solta num serviço. A implementação é o {@code BCryptPasswordEncoder} do Spring Security, ligado por um
 * lambda na configuração — trocar BCrypt por Argon2 não toca em nada aqui.
 */
@FunctionalInterface
public interface PasswordVerifier {

    boolean matches(String rawPassword, String encodedPassword);
}
