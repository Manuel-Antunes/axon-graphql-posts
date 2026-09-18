package dev.manuelantunes.axonposts.domain.shared;

import java.time.Instant;

/**
 * O <b>contrato</b> de exclusão lógica: o vocabulário de uma entidade apagável, e nada sobre onde ela
 * guarda esse estado.
 *
 * <h2>Duas perguntas, dois pares de métodos</h2>
 * <ul>
 *   <li><b>decidir</b> — {@link #delete(Instant)} e {@link #restore()} verificam antes de mudar: apagar o
 *       que já está apagado não registra fato novo, e restaurar o que está vivo tampouco;</li>
 *   <li><b>evoluir</b> — {@link #applyDeletion(Instant)} e {@link #applyRestoration()} aplicam sem
 *       perguntar nada, porque o fato já aconteceu.</li>
 * </ul>
 * É a mesma divisão que o resto do projeto faz, e num agregado event-sourced ela é obrigatória, não
 * estética: o mesmo {@code PostDeletedEvent} é aplicado <b>duas vezes</b> — o domínio o aplica ao decidir,
 * para devolver a entidade pronta para salvar, e o Axon o aplica de novo ao apendar. Um
 * {@code @EventSourcingHandler} que chamasse {@link #delete(Instant)} estouraria na segunda. Para quem não
 * é event-sourced (o {@code User}), o par de evoluir não tem uso: lá só existe decidir.
 *
 * <h2>Por que uma interface, e não uma superclasse</h2>
 * {@code Post} e {@code User} não têm nem poderiam ter um ancestral comum — um é agregado event-sourced
 * com stream próprio, o outro é a raiz de uma hierarquia {@code JOINED}. Java tem herança simples, e
 * {@code User} já gastou a dele com {@code Author}. Uma interface atravessa hierarquias sem tocar nelas,
 * que é exatamente o problema aqui: "ser apagável" é ortogonal a "ser post" ou "ser usuário".
 *
 * <h2>Quem implementa isto</h2>
 * Ninguém à mão: {@link EmbeddableSoftDeletable} já entrega os seis métodos a partir de duas propriedades
 * ({@link #softDeletion()} e {@link #identity()}), e é o único caminho que o projeto usa.
 * <p>
 * Os dois arquivos são separados de propósito. Este diz <b>o que a entidade é</b> — é o tipo que os
 * chamadores enxergam e o que uma assinatura pede; o outro diz <b>de onde ela tira o estado</b>. Trocar a
 * segunda resposta não mexe na primeira, que é a razão de as entidades declararem as duas coisas em
 * linhas separadas ({@code implements SoftDeletable, EmbeddableSoftDeletable}).
 *
 * <h2>A parte que este contrato não resolve, e não deveria</h2>
 * Apagar e restaurar mudam o objeto <b>em memória</b>. Fazer isso chegar ao banco — e, mais delicado,
 * fazer uma linha escondida por {@code @SQLRestriction} voltar a aparecer — é responsabilidade do adapter
 * de persistência. Ver {@code PostRepository.restore(...)}: a linha apagada é invisível para o JPA, então
 * {@link #restore()} sozinho nunca a traria de volta.
 * <p>
 * A separação é essa: aqui se decide <i>se</i> pode e <i>o que</i> muda; o repositório sabe <i>como</i>
 * gravar.
 */
public interface SoftDeletable {

    /**
     * Onde o estado de exclusão mora nesta entidade. Uma das duas propriedades que a implementação pede.
     * <p>
     * Devolve o holder, e não o {@code Instant}, porque é o holder que sabe mudar — ver
     * {@link SoftDeletion}.
     */
    SoftDeletion softDeletion();

    /**
     * Como esta entidade se identifica numa mensagem de erro. Normalmente o id.
     */
    Object identity();

    boolean isDeleted();

    /**
     * {@code null} enquanto viva.
     */
    Instant deletedAt();

    /**
     * Marca como apagada.
     *
     * @throws AlreadyDeletedException se já estiver apagada — não há fato novo a registrar
     */
    void delete(Instant now);

    /**
     * Desmarca.
     *
     * @throws NotDeletedException se não estiver apagada
     */
    void restore();

    // ---- evoluir: aplicar sem decidir -----------------------------------------------------------

    /**
     * Aplica a exclusão <b>sem verificar</b> nada, a partir de um fato que já aconteceu. É o que um
     * {@code @EventSourcingHandler} chama; ver a divisão decidir/evoluir no javadoc da interface.
     */
    void applyDeletion(Instant at);

    /**
     * A contraparte de {@link #applyDeletion}, pelo mesmo motivo.
     */
    void applyRestoration();
}
