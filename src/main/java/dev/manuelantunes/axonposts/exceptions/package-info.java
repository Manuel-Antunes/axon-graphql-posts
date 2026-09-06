/**
 * <b>Tradução de exceções para o protocolo.</b> Um único lugar decide como cada falha aparece para o
 * cliente GraphQL — {@code BAD_REQUEST}, {@code NOT_FOUND}, {@code INTERNAL_ERROR} — venha ela da
 * Bean Validation, do domínio ou do Axon.
 * <p>
 * As <b>exceções</b> em si continuam onde nascem: {@code InvalidPostException} e
 * {@code PostAlreadyExistsException} moram em {@code domain.post.exception} porque fazem parte do
 * vocabulário do domínio — ele as lança sem saber que existe HTTP, GraphQL ou {@code ErrorType}. O que
 * mora aqui é quem <i>ouve</i> essas exceções na borda.
 */
package dev.manuelantunes.axonposts.exceptions;
