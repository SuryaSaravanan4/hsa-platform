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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

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

    @Inject
    LedgerRepository ledger;

    @Inject
    QualifiedExpensePolicy policy;

    /** Public on purpose: Quarkus only applies @Transactional to non-private methods called through the bean. */
    @Transactional
    public TxResult purchase(long cardId, long amountCents, String merchantName,
                             MerchantCategory category, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<HsaTransaction> existing = HsaTransaction.byIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                HsaTransaction prior = existing.get();
                Idempotency.requireSameRequest(prior, prior.accountId, cardId, amountCents);
                return TxResult.replay(prior, ledger.balanceOf(prior.accountId));
            }
        }

        // Shared lock (SELECT ... FOR SHARE): concurrent purchases on the same card don't block
        // each other here, but a concurrent freeze waits until this purchase commits, so a card
        // can't be frozen "in the middle" of an authorization.
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
        tx.idempotencyKey = idempotencyKey;
        tx.createdAt = Instant.now();

        DeclineReason decline;
        if (!card.isUsableOn(LocalDate.now(ZoneOffset.UTC))) {
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
        return TxResult.fresh(tx, ledger.balanceOf(card.accountId));
    }
}
