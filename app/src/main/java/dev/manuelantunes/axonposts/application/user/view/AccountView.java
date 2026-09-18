package dev.manuelantunes.axonposts.application.user.view;

import java.time.Instant;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import dev.manuelantunes.axonposts.domain.user.AuthProvider;

/**
 * DTO de saída de uma credencial: o {@code type Account} do schema.
 * <p>
 * {@code hasPassword} é o campo que torna o account linking legível para um cliente: com ele a interface
 * sabe se deve oferecer "entrar com senha" ou só "entrar com o provedor". Vem do
 * {@code Authenticatable.hasPassword()} — o mixin, atravessando até a borda.
 * <p>
 * Nenhum segredo passa por aqui: o hash fica na entidade, e o que sai é só a resposta de sim ou não.
 *
 * <h2>{@code provider} é o enum de domínio, e não uma String</h2>
 * No schema escrito à mão do projeto Spring o campo era {@code AuthProvider!} enquanto o DTO carregava
 * {@code String} — o SDL e o Java discordavam, e ninguém era obrigado a notar. Num schema code-first a
 * discordância não é possível: o enum <b>é</b> o tipo, e acrescentar um provedor ao domínio acrescenta o
 * valor ao schema no mesmo commit.
 */
@Name("Account")
@Description("Uma credencial. Identidade em `users`, credencial em `accounts`, uma linha por provedor")
public record AccountView(
        @NonNull AuthProvider provider,
        @NonNull @Description("O identificador da conta NO provedor (o `sub` do token). Não é o e-mail")
        String subject,
        @Description("Se dá para entrar com senha por esta conta. Falso para toda conta federada")
        boolean hasPassword,
        @NonNull Instant linkedAt
) {
}
