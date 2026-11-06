package com.example.hsa.service;

import com.example.hsa.domain.HsaTransaction;

import java.util.Objects;

final class Idempotency {

    private Idempotency() {
    }

    /**
     * A reused idempotency key must describe the same request. Reusing a key for a different
     * amount, account or card is a client bug, so it is rejected instead of silently replayed.
     */
    static void requireSameRequest(HsaTransaction prior, long accountId, Long cardId, long amountCents) {
        boolean same = prior.accountId == accountId
                && Objects.equals(prior.cardId, cardId)
                && prior.amountCents == amountCents;
        if (!same) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency key was already used for a different request");
        }
    }
}
