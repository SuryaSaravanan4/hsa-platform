package com.example.hsa.service;

import com.example.hsa.domain.Card;
import com.example.hsa.domain.DeclineReason;
import com.example.hsa.domain.HsaTransaction;
import com.example.hsa.domain.MerchantCategory;
import com.example.hsa.domain.QualifiedExpensePolicy;
import com.example.hsa.domain.TransactionStatus;
import com.example.hsa.domain.TransactionType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Authorizes a card purchase. Order of checks:
 * <ol>
 *   <li>card exists (404 otherwise) and is ACTIVE and unexpired, else CARD_INACTIVE</li>
 *   <li>merchant category is a qualified medical expense, else NOT_QUALIFIED</li>
 *   <li>atomic conditional debit succeeds, else INSUFFICIENT_FUNDS</li>
 * </ol>
 * Every outcome, approved or declined, is written as one hsa_transaction row in the same
 * database transaction as the debit.
 */
@ApplicationScoped
public class PurchaseService {

    private static final Logger LOG = Logger.getLogger(PurchaseService.class);

    @Inject
    LedgerRepository ledger;

    @Inject
    QualifiedExpensePolicy policy;

    @Inject
    Clock clock;

    /** Public on purpose: Quarkus only applies @Transactional to non-private methods called through the bean. */
    @Transactional
    public TxResult purchase(long cardId, long amountCents, String merchantName, MerchantCategory category) {
        // Shared lock (SELECT ... FOR SHARE): concurrent purchases on the same card don't block
        // each other here, but a concurrent freeze waits until this purchase commits, so a card
        // can't be frozen "in the middle" of an authorization. A purchase arriving during an
        // uncommitted freeze waits for it and then sees FROZEN; a plain read would see the old
        // ACTIVE row and approve. Proven by RaceConditionTest.purchaseWaitsForInFlightFreezeAndIsDeclined.
        Card card = Card.findById(cardId, LockModeType.PESSIMISTIC_READ);
        if (card == null) {
            throw ApiException.notFound("Card", cardId);
        }

        HsaTransaction tx = new HsaTransaction();
        tx.accountId = card.accountId;
        tx.cardId = card.id;
        tx.type = TransactionType.PURCHASE;
        tx.amountCents = amountCents;
        tx.merchantName = merchantName;
        tx.merchantCategory = category;
        tx.createdAt = Instant.now(clock);

        DeclineReason decline;
        if (!card.isUsableOn(LocalDate.now(clock))) {
            decline = DeclineReason.CARD_INACTIVE;
        } else if (!policy.isQualified(category)) {
            decline = DeclineReason.NOT_QUALIFIED;
        } else if (!ledger.tryDebit(card.accountId, amountCents)) {
            decline = DeclineReason.INSUFFICIENT_FUNDS;
        } else {
            decline = null;
        }

        tx.status = decline == null ? TransactionStatus.APPROVED : TransactionStatus.DECLINED;
        tx.declineReason = decline;
        tx.persistAndFlush();
        // One key=value line per decision, so declines can be searched and counted in the logs.
        LOG.infof("purchase_decision txId=%d cardId=%d accountId=%d amountCents=%d category=%s outcome=%s declineReason=%s",
                tx.id, card.id, card.accountId, amountCents, category, tx.status, decline);
        return new TxResult(tx, ledger.balanceOf(card.accountId));
    }
}
