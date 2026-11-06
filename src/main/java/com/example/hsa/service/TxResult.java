package com.example.hsa.service;

import com.example.hsa.domain.HsaTransaction;

/**
 * @param balanceAfterCents account balance right after this transaction (for a replay: the current balance)
 * @param replayed          true if returned for a repeated idempotency key instead of being newly created
 */
public record TxResult(HsaTransaction tx, long balanceAfterCents, boolean replayed) {

    static TxResult fresh(HsaTransaction tx, long balance) {
        return new TxResult(tx, balance, false);
    }

    static TxResult replay(HsaTransaction tx, long balance) {
        return new TxResult(tx, balance, true);
    }
}
