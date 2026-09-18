/**
 * <b>Input do GraphQL → command da aplicação</b>, em MapStruct — o salto de fora para dentro.
 * <p>
 * É o ponto em que o dado deixa de ser "o que o cliente mandou" e vira "o que a aplicação executa". O
 * salto de volta (entidade → view) não está aqui: ele é da aplicação, e mora ao lado das views que produz.
 *
 * <h2>O que o MapStruct compra</h2>
 * Código gerado em tempo de compilação, em {@code target/generated-sources/annotations}: Java comum,
 * depurável, sem reflexão em runtime. E com {@code -Amapstruct.unmappedTargetPolicy=ERROR} (ver
 * {@code pom.xml}), um campo novo no command que ninguém preencheu <b>quebra o build</b> em vez de chegar
 * {@code null} do outro lado.
 * <p>
 * Nenhum {@code @Mapper} do projeto declara {@code componentModel}: o {@code pom.xml} passa
 * {@code -Amapstruct.defaultComponentModel=jakarta-cdi}, então todo mapper gerado já nasce
 * {@code @ApplicationScoped} e injetável. É a diferença para a versão Spring, onde cada anotação repetia
 * {@code componentModel = "spring"} — e onde esquecer a repetição dava um mapper que compila e não é
 * encontrado.
 */
package dev.manuelantunes.axonposts.interfaces.graphql.mapper;
