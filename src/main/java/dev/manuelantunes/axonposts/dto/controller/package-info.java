/**
 * <b>DTOs de entrada dos controllers.</b> São a forma que o dado tem <i>no protocolo</i>: espelham os
 * {@code input} do schema GraphQL, carregam as constraints de Bean Validation e nada mais.
 * <p>
 * Deliberadamente não são commands nem value objects: um input pode chegar inválido — é justamente o que
 * ele existe para descrever. Quem o transforma em command é o {@code PostInputMapper}, e é só depois
 * disso que o dado entra na aplicação.
 */
package dev.manuelantunes.axonposts.dto.controller;
