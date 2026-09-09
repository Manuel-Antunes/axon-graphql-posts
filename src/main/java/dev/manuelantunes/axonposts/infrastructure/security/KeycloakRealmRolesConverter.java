package dev.manuelantunes.axonposts.infrastructure.security;

import dev.manuelantunes.axonposts.domain.user.Role;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Claims do Keycloak → authorities do Spring Security.
 *
 * <h2>Por que um conversor à mão</h2>
 * O {@code JwtGrantedAuthoritiesConverter} de fábrica só lê claims de <b>primeiro nível</b>
 * ({@code scope}, {@code scp}, ou uma que se configure). O Keycloak guarda as roles de realm aninhadas:
 * <pre>{ "realm_access": { "roles": ["author", "default-roles-axon-posts"] } }</pre>
 * Não há como apontar o conversor padrão para dentro de um objeto, então o caminho é este.
 *
 * <h2>O prefixo, e o cuidado com ele</h2>
 * O Keycloak escreve {@code author}; o Spring procura {@code ROLE_AUTHOR} quando se pede
 * {@code hasRole('AUTHOR')}. O prefixo é acrescentado aqui, uma vez. Antes da migração era o emissor
 * local que já mandava {@code ROLE_AUTHOR} pronto e o conversor tinha de <b>não</b> prefixar — invertido,
 * o resultado seria {@code ROLE_ROLE_AUTHOR} e nenhuma checagem casaria.
 *
 * <h2>Só as roles que este sistema conhece</h2>
 * As roles do realm que não correspondem a nenhum valor de {@link Role} são descartadas
 * ({@code default-roles-*}, {@code offline_access}, {@code uma_authorization}). Não é limpeza estética:
 * é o mesmo princípio do enum fechado — o que o Keycloak inventar de novo não vira permissão aqui sem
 * alguém decidir em tempo de compilação.
 */
public class KeycloakRealmRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String REALM_ACCESS = "realm_access";
    static final String ROLES = "roles";

    /** Os nomes que este sistema reconhece, em minúsculas, como o Keycloak os escreve. */
    private static final Set<String> KNOWN = Set.of("user", "author");

    @Override
    @SuppressWarnings("unchecked")
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim(REALM_ACCESS);
        if (realmAccess == null || !(realmAccess.get(ROLES) instanceof Collection<?> roles)) {
            return List.of();
        }

        return ((Collection<Object>) roles).stream()
                .map(String::valueOf)
                .map(role -> role.toLowerCase(Locale.ROOT))
                .filter(KNOWN::contains)
                .map(role -> Role.valueOf(role.toUpperCase(Locale.ROOT)))
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role.authority()))
                .toList();
    }

    /** {@code true} se o token traz a role de autor — usado pelo provisionamento, sem passar por Spring. */
    @SuppressWarnings("unchecked")
    public static boolean hasAuthorRole(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim(REALM_ACCESS);
        if (realmAccess == null || !(realmAccess.get(ROLES) instanceof Collection<?> roles)) {
            return false;
        }
        return ((Collection<Object>) roles).stream()
                .map(String::valueOf)
                .anyMatch(role -> role.equalsIgnoreCase(Role.AUTHOR.name()));
    }
}
