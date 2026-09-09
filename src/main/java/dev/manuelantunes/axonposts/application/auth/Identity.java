package dev.manuelantunes.axonposts.application.auth;

import dev.manuelantunes.axonposts.domain.user.AuthProvider;

/**
 * O que o token do Keycloak afirma, traduzido para o vocabulário do domínio.
 *
 * <h2>Por que existe este record em vez de passar o {@code Jwt} adiante</h2>
 * Porque {@code org.springframework.security.oauth2.jwt.Jwt} é tipo de framework, e deixá-lo entrar no
 * provisionamento significaria que trocar de broker (ou de biblioteca) mexeria em regra de negócio. Este
 * record é a fronteira: o que vem depois dele não sabe o que é uma claim.
 *
 * @param provider  quem de fato garantiu a identidade — o Keycloak, ou o provedor social que ele
 *                  intermediou (claim {@code identity_provider})
 * @param subject   o {@code sub}: identificador estável no provedor
 * @param email     usado para <b>ligar</b> a conta a um usuário local já existente
 * @param name      nome de exibição; cai no e-mail se o token não trouxer
 * @param author    se o token traz a role {@code author} em {@code realm_access.roles}
 */
public record Identity(
        AuthProvider provider,
        String subject,
        String email,
        String name,
        boolean author
) {
}
