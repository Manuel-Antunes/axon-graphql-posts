package dev.manuelantunes.axonposts.domain.shared;

import java.time.Instant;

/**
 * <b>Mixin</b> de exclusão lógica: quem implementa ganha {@code delete}, {@code restore} e
 * {@code isDeleted} prontos, e só precisa dizer <i>onde</i> o estado mora.
 *
 * <h2>O contrato, em duas metades</h2>
 * <ul>
 *   <li><b>o que você implementa</b>: {@link #softDeletion()} e {@link #identity()} — as propriedades
 *       que o mixin não tem como saber sozinho;</li>
 *   <li><b>o que você ganha</b>: os três métodos default abaixo, com as regras já dentro.</li>
 * </ul>
 * É a forma que um mixin toma em Java: a interface não guarda estado (não pode), então ela pede o estado
 * por acessor e entrega comportamento por {@code default}.
 *
 * <h2>Por que isto não é herança</h2>
 * {@code Post} e {@code User} não têm nem poderiam ter um ancestral comum — um é agregado event-sourced
 * com stream próprio, o outro é a raiz de uma hierarquia {@code JOINED}. Java tem herança simples, e
 * {@code User} já gastou a dele com {@code Author}. Um mixin atravessa hierarquias sem tocar nelas, que
 * é exatamente o problema aqui: "ser apagável" é ortogonal a "ser post" ou "ser usuário".
 *
 * <h2>A parte que o mixin não resolve, e não deveria</h2>
 * Os defaults mudam o objeto <b>em memória</b>. Fazer isso chegar ao banco — e, mais delicado, fazer uma
 * linha escondida por {@code @SQLRestriction} voltar a aparecer — é responsabilidade do adapter de
 * persistência. Ver {@code PostRepository.restore(...)}: a linha apagada é invisível para o JPA, então
 * {@link #restore()} sozinho nunca a traria de volta.
 * <p>
 * A separação é essa: o mixin decide <i>se</i> pode e <i>o que</i> muda; o repositório sabe <i>como</i>
 * gravar.
 */
public interface SoftDeletable {

    /**
     * Onde o estado de exclusão mora nesta entidade. Único acessor obrigatório do mixin.
     * <p>
     * Devolve o holder, e não o {@code Instant}, porque é o holder que sabe mudar — ver
     * {@link SoftDeletion}.
     */
    SoftDeletion softDeletion();

    /** Como esta entidade se identifica numa mensagem de erro. Normalmente o id. */
    Object identity();

    default boolean isDeleted() {
        return softDeletion().isDeleted();
    }

    /** {@code null} enquanto viva. */
    default Instant deletedAt() {
        return softDeletion().at();
    }

    /**
     * Marca como apagada.
     *
     * @throws AlreadyDeletedException se já estiver apagada — não há fato novo a registrar
     */
    default void delete(Instant now) {
        if (isDeleted()) {
            throw new AlreadyDeletedException(identity());
        }
        softDeletion().delete(now);
    }

    /**
     * Desmarca.
     *
     * @throws NotDeletedException se não estiver apagada
     */
    default void restore() {
        if (!isDeleted()) {
            throw new NotDeletedException(identity());
        }
        softDeletion().restore();
    }

    // ---- evoluir: aplicar sem decidir -----------------------------------------------------------

    /**
     * Aplica a exclusão <b>sem verificar</b> nada.
     *
     * <h3>Por que existe um segundo par de métodos</h3>
     * É a mesma divisão que o resto do projeto faz entre <i>decidir</i> e <i>evoluir</i>.
     * {@link #delete(Instant)} decide: recusa se já estiver apagado, porque não haveria fato novo.
     * Este aqui só evolui o estado a partir de um fato que já aconteceu.
     * <p>
     * Num agregado event-sourced a diferença é obrigatória, não estética: o mesmo
     * {@code PostDeletedEvent} é aplicado <b>duas vezes</b> — o domínio aplica ao decidir, para devolver
     * a entidade pronta para salvar, e o Axon aplica de novo ao apendar. Um {@code @EventSourcingHandler}
     * que chamasse {@link #delete(Instant)} estouraria na segunda.
     * <p>
     * Para quem não é event-sourced (o {@code User}), estes não têm uso: lá só existe decidir.
     */
    default void applyDeletion(Instant at) {
        softDeletion().delete(at);
    }

    /** A contraparte de {@link #applyDeletion}, pelo mesmo motivo. */
    default void applyRestoration() {
        softDeletion().restore();
    }
}
