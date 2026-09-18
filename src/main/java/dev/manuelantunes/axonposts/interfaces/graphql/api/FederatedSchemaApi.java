package dev.manuelantunes.axonposts.interfaces.graphql.api;

import org.eclipse.microprofile.graphql.GraphQLApi;

import io.smallrye.graphql.api.federation.link.Import;
import io.smallrye.graphql.api.federation.link.Link;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * O {@code @link} da Federação 2 — a única declaração de <b>schema</b> deste subgraph.
 *
 * <h2>Por que uma classe sem nenhuma operação</h2>
 * {@code @Link} é uma diretiva de {@code SCHEMA}, e o SmallRye só recolhe diretivas de schema em classes
 * anotadas com {@code @GraphQLApi} — a varredura é por classe de API, não por pacote. Ou ela mora numa
 * das APIs existentes, onde não tem nada a ver com os resolvers ao lado, ou mora sozinha. Sozinha é o
 * que deixa a declaração achável por quem procura "onde está a versão da federação".
 * <p>
 * <b>Só pode existir uma.</b> O {@code LinkProcessor} recusa dois {@code @link} para a especificação da
 * Federação na partida — repetir a anotação em outra API derruba a aplicação com "Multiple @link
 * directives that import Federation spec found on schema".
 *
 * <h2>O que acontece sem o {@code import}, e por que isso importa</h2>
 * Sem nenhum {@code @Link} o SmallRye emite as diretivas com o nome curto ({@code @key}). Com um
 * {@code @Link} que <b>não</b> importe a diretiva usada, ela sai prefixada ({@code @federation__key}) —
 * as duas formas compõem, mas só a segunda obriga o leitor do SDL a saber o que é. Importar
 * exatamente o que se usa é o que mantém o SDL publicado legível.
 *
 * <h2>A versão é literal de propósito</h2>
 * {@link Link#FEDERATION_SPEC_LATEST_URL} existe e apontaria para a mais nova que a biblioteca conhece —
 * e mudaria sozinha no próximo upgrade. A versão do {@code @link} é o que determina quais diretivas o
 * roteador aceita deste subgraph: é contrato, e contrato não muda por efeito colateral de bump de
 * dependência. Subir a versão é editar esta linha, e o {@code FederationSchemaTest} confere o valor
 * publicado.
 * <p>
 * {@code v2.7} é a mais nova suportada pelo SmallRye 2.18.5; {@code @key} e {@code @shareable} existem
 * desde a 2.0, então as duas cabem com folga.
 */
@GraphQLApi
@ApplicationScoped
@SuppressWarnings("WRONG_DIRECTIVE_PLACEMENT")
@Link(
        url = "https://specs.apollo.dev/federation/v2.7",
        _import = {
                @Import(name = "@key"),
                @Import(name = "@shareable")
        })
public class FederatedSchemaApi {
}
