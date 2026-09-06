/**
 * <b>Todos os mapeamentos do projeto, em um lugar só</b>, e todos gerados pelo MapStruct.
 * <p>
 * A regra é: precisou traduzir um tipo em outro, o mapper mora aqui e é MapStruct — não há mapeamento
 * escrito à mão espalhado por controller, handler ou repositório. Os três saltos que a informação dá,
 * de fora para dentro e de volta:
 * <ol>
 *   <li>{@code PostInputMapper}: input do GraphQL → command da aplicação (protocolo → aplicação);</li>
 *   <li>{@code PostViewMapper}: entidade de domínio → read model (domínio → aplicação);</li>
 *   <li>{@code PostEntityMapper}: read model ↔ entidade JPA (aplicação ↔ infraestrutura).</li>
 * </ol>
 *
 * <h2>O que o MapStruct compra aqui</h2>
 * O código é gerado em tempo de compilação e fica em {@code target/generated-sources/annotations}: é
 * Java comum, depurável, sem reflexão em runtime. E com
 * {@code -Amapstruct.unmappedTargetPolicy=ERROR} (ver {@code pom.xml}), um campo novo no destino que
 * ninguém preencheu <b>quebra o build</b> em vez de chegar {@code null} do outro lado. Foi essa garantia
 * que motivou concentrar tudo num mapper só por salto.
 *
 * <h2>O preço de agrupar por tipo</h2>
 * Um pacote de mappers na raiz enxerga todas as camadas — inclusive a entidade JPA, que de outra forma
 * ficaria fechada dentro de {@code infrastructure.persistence.sqlite}. É a troca consciente de
 * "agrupado por camada" por "agrupado por papel": fácil achar todo mapeamento, ao custo de um pacote que
 * cruza fronteiras.
 */
package dev.manuelantunes.axonposts.mapper;
