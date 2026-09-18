/**
 * <b>Os {@code input} do schema.</b> São a forma que o dado tem <i>no protocolo</i>: espelham os
 * {@code input type} do GraphQL, carregam as constraints de Bean Validation e nada mais.
 * <p>
 * Deliberadamente não são commands nem value objects: um input pode chegar inválido — é justamente o que
 * ele existe para descrever. Quem o transforma em command é o {@code PostInputMapper}, e é só depois disso
 * que o dado entra na aplicação. Por isso este pacote é o único dos DTOs que fica na apresentação: nada
 * daqui atravessa o command bus.
 * <p>
 * O {@code @Input("CreatePostInput")} não é decorativo: num schema code-first o SmallRye nomeia um input
 * acrescentando o sufixo {@code Input} ao nome da classe, o que daria {@code CreatePostInputInput}.
 */
package dev.manuelantunes.axonposts.interfaces.graphql.dto;
