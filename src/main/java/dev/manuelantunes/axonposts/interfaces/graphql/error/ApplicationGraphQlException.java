package dev.manuelantunes.axonposts.interfaces.graphql.error;

/**
 * Raiz das exceções que já <b>sabem como querem aparecer</b> no protocolo.
 * <p>
 * Só quatro subclasses existem, uma por classificação, e cada uma leva um {@code @ErrorCode} do SmallRye
 * — que vira {@code errors[].extensions.code} na resposta. É o equivalente ao {@code ErrorType} do Spring
 * GraphQL, com a diferença de ser um tipo Java: o compilador confere qual classificação você escolheu, e
 * {@code GraphQlErrors} é o único lugar que as constrói.
 * <p>
 * Para a mensagem chegar ao cliente, cada subclasse precisa estar listada em
 * {@code quarkus.smallrye-graphql.show-runtime-exception-message} — sem isso o SmallRye responde
 * "System Error" para qualquer exceção não-checada, que é o comportamento certo por padrão e errado
 * para estas quatro.
 */
public abstract class ApplicationGraphQlException extends RuntimeException {

    protected ApplicationGraphQlException(String message) {
        super(message);
    }
}
