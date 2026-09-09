package dev.manuelantunes.axonposts.dto.controller;

import jakarta.validation.constraints.NotBlank;

/** Input GraphQL de {@code login}. As credenciais nunca aparecem em log — nem aqui, nem no erro. */
public record LoginInput(

        @NotBlank(message = "email não pode ser vazio")
        String email,

        @NotBlank(message = "password não pode ser vazio")
        String password
) {

    /** Sobrescrito para que um log acidental do input não vaze a senha. */
    @Override
    public String toString() {
        return "LoginInput[email=" + email + ", password=protegido]";
    }
}
