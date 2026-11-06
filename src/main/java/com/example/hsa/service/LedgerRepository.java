package com.example.hsa.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * The only code that changes account.balance_cents.
 *
 * <p>Both operations are a single SQL statement, so the check and the change happen atomically
 * inside MySQL. InnoDB takes an exclusive row lock on the account row for the UPDATE, so
 * concurrent callers for the same account are serialized by the database, not by the JVM. This
 * stays correct with any number of app instances.
 *
 * <p>Deliberately NOT done: load the Account entity, compare the balance in Java, then save. That
 * read-check-write sequence lets two concurrent purchases both see $100 and both succeed.
 *
 * <p>MANDATORY: these must run inside the caller's transaction so the balance change and the
 * hsa_transaction row commit or roll back together.
 */
@ApplicationScoped
public class LedgerRepository {

    @Inject
    EntityManager em;

    /**
     * Subtracts {@code amountCents} only if the balance covers it.
     *
     * @return true if the debit happened (exactly one row updated), false if funds were
     *         insufficient or the account does not exist (zero rows updated; nothing changed).
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean tryDebit(long accountId, long amountCents) {
        requirePositive(amountCents);
        int rows = em.createNativeQuery("""
                        UPDATE account
                           SET balance_cents = balance_cents - :amount
                         WHERE id = :accountId AND balance_cents >= :amount
                        """)
                .setParameter("amount", amountCents)
                .setParameter("accountId", accountId)
                .executeUpdate();
        return rows == 1;
    }

    /** Adds funds. Also one atomic statement, so concurrent deposits never lose an update. */
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean credit(long accountId, long amountCents) {
        requirePositive(amountCents);
        int rows = em.createNativeQuery(
                        "UPDATE account SET balance_cents = balance_cents + :amount WHERE id = :accountId")
                .setParameter("amount", amountCents)
                .setParameter("accountId", accountId)
                .executeUpdate();
        return rows == 1;
    }

    /** Balance as seen by the current transaction (includes its own uncommitted changes). */
    @Transactional(Transactional.TxType.MANDATORY)
    public long balanceOf(long accountId) {
        Object v = em.createNativeQuery("SELECT balance_cents FROM account WHERE id = :accountId")
                .setParameter("accountId", accountId)
                .getSingleResult();
        return ((Number) v).longValue();
    }

    private static void requirePositive(long amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive, was " + amountCents);
        }
    }
}
