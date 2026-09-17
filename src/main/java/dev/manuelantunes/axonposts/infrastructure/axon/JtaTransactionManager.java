package dev.manuelantunes.axonposts.infrastructure.axon;

import org.axonframework.messaging.core.unitofwork.transaction.Transaction;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.UserTransaction;

/**
 * A porta de transação do Axon ligada ao JTA do Quarkus (Narayana).
 *
 * <h2>O que ela substitui</h2>
 * Na versão Spring, quem fazia este papel era o {@code SpringTransactionManager} do
 * {@code axon-spring}, sobre um {@code PlatformTransactionManager}. O Quarkus não tem essa abstração: a
 * transação é JTA e o ponto de entrada programático é {@link UserTransaction}. São vinte linhas, e elas
 * são a razão de o {@code @Transactional} dos repositórios Panache <b>entrar</b> na transação que o
 * command abriu, em vez de abrir uma própria — é o que faz o evento no event store e a linha no Postgres
 * commitarem juntos.
 *
 * <h2>Transação já ativa</h2>
 * {@link #startTransaction()} devolve uma transação <b>inerte</b> quando já existe uma ativa na thread.
 * Sem essa guarda, {@code begin()} estouraria com {@code NotSupportedException} (JTA não aninha), e um
 * {@code commit()} de dentro fecharia a transação de quem chamou. É a mesma semântica de
 * {@code PROPAGATION_REQUIRED}: quem abriu, fecha.
 */
@ApplicationScoped
public class JtaTransactionManager implements TransactionManager {

    /** A transação que não faz nada: já havia uma ativa, e quem a abriu é quem vai fechá-la. */
    private static final Transaction JOINED = new Transaction() {
        @Override
        public void commit() {
            // no-op: participa da transação de quem chamou
        }

        @Override
        public void rollback() {
            // no-op: quem abriu decide o desfecho
        }
    };

    private final UserTransaction userTransaction;

    public JtaTransactionManager(UserTransaction userTransaction) {
        this.userTransaction = userTransaction;
    }

    @Override
    public Transaction startTransaction() {
        try {
            if (userTransaction.getStatus() != Status.STATUS_NO_TRANSACTION) {
                return JOINED;
            }
            userTransaction.begin();
            return new UserTransactionAdapter(userTransaction);
        } catch (Exception e) {
            throw new IllegalStateException("não foi possível iniciar a transação JTA", e);
        }
    }

    private record UserTransactionAdapter(UserTransaction userTransaction) implements Transaction {

        @Override
        public void commit() {
            try {
                userTransaction.commit();
            } catch (Exception e) {
                throw new IllegalStateException("falha ao commitar a transação JTA", e);
            }
        }

        /**
         * O {@code rollback} chega pelo {@code onError} do {@code ProcessingLifecycle}, que pode disparar
         * depois de a transação já ter sido desfeita pelo próprio container. Por isso a checagem de
         * status: um {@code rollback()} sobre "nenhuma transação" lançaria por cima do erro original e
         * esconderia a causa de verdade.
         */
        @Override
        public void rollback() {
            try {
                if (userTransaction.getStatus() != Status.STATUS_NO_TRANSACTION) {
                    userTransaction.rollback();
                }
            } catch (SystemException e) {
                throw new IllegalStateException("falha ao desfazer a transação JTA", e);
            }
        }
    }
}
