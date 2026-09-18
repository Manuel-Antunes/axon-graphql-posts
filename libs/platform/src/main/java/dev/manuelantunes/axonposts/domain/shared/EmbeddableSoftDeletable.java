package dev.manuelantunes.axonposts.domain.shared;

import java.time.Instant;

/**
 * O <b>mixin</b> que implementa {@link SoftDeletable} para quem guarda o estado num {@link SoftDeletion}
 * {@code @Embeddable} — hoje {@code Post} e {@code User}, e é o único jeito que o projeto usa.
 *
 * <h2>O contrato, em duas metades</h2>
 * <ul>
 *   <li><b>o que você implementa</b>: {@link #softDeletion()} e {@link #identity()} — as propriedades que
 *       o mixin não tem como saber sozinho;</li>
 *   <li><b>o que você ganha</b>: os seis métodos abaixo, com as regras já dentro.</li>
 * </ul>
 * É a forma que um mixin toma em Java: a interface não guarda estado (não pode), então pede o estado por
 * acessor e entrega comportamento por {@code default}. As regras e o porquê de cada método estão no
 * contrato, e os javadoc são herdados de lá — aqui não há decisão nova, só delegação ao holder.
 * <p>
 * Que o comportamento não dependa de JPA nenhum é o que deixa {@code SoftDeletableTest} exercitá-lo
 * sozinho, sobre uma classe de teste de quatro linhas: as duas entidades herdam o teste junto com o
 * código.
 *
 * <h2>O que a entidade ainda precisa trazer</h2>
 * O campo mapeado — {@code @Embedded private SoftDeletion softDeletion = new SoftDeletion()} — e um
 * acessor que devolva {@code new SoftDeletion()} quando o campo estiver nulo.
 * <p>
 * A guarda não é paranoia: quando <b>todas</b> as colunas de um {@code @Embedded} vêm nulas — o caso de
 * toda entidade viva, já que {@code deleted_at} é a única — o Hibernate deixa o componente inteiro nulo em
 * vez de instanciar um vazio, e {@link #isDeleted()} estouraria em qualquer entidade lida do banco. O
 * inicializador do campo cobre as instâncias construídas em Java; a guarda no acessor cobre as hidratadas
 * pelo ORM. As duas são necessárias, e foi um teste contra o banco de verdade que mostrou a segunda.
 *
 * <h2>E não, o mixin não tem como trazer o campo junto</h2>
 * Interface não tem estado, e introdução de AspectJ não é saída: o campo que o weaver cria com
 * {@code @DeclareParents}/{@code @DeclareMixin} não aceita {@code @Embedded} nem {@code @AttributeOverride},
 * o Hibernate não o mapeia, e o {@code @SQLRestriction}/{@code @SQLDelete} das entidades passaria a apontar
 * para uma coluna que ninguém escreve. A variante do Spring AOP nem chega perto: é por proxy de bean, e
 * estas entidades nascem do {@code new}, do Hibernate e do {@code @EntityCreator} do Axon.
 * <p>
 * Ou seja: o que um weaver removeria são os métodos, que os {@code default} já dão de graça; o que sobra
 * repetido é estado persistido, e esse é irredutível.
 */
public interface EmbeddableSoftDeletable extends SoftDeletable {

    default boolean isDeleted() {
        return softDeletion().isDeleted();
    }

    default Instant deletedAt() {
        return softDeletion().at();
    }

    default void delete(Instant now) {
        if (isDeleted()) {
            throw new AlreadyDeletedException(identity());
        }
        softDeletion().delete(now);
    }

    default void restore() {
        if (!isDeleted()) {
            throw new NotDeletedException(identity());
        }
        softDeletion().restore();
    }

    // ---- evoluir: aplicar sem decidir -----------------------------------------------------------

    default void applyDeletion(Instant at) {
        softDeletion().delete(at);
    }

    default void applyRestoration() {
        softDeletion().restore();
    }
}
