package com.example.hsa.service;

import java.util.Locale;

/** Helpers for recognising which database constraint a persistence exception came from. */
public final class Constraints {

    public static final String UQ_ACCOUNT_EMAIL = "uq_account_email";
    public static final String UQ_ONE_ACTIVE_CARD = "uq_card_one_active_per_account";
    public static final String UQ_TX_IDEMPOTENCY_KEY = "uq_tx_idempotency_key";

    private Constraints() {
    }

    /** True if any exception in the cause chain mentions the named constraint. */
    public static boolean isViolation(Throwable t, String constraintName) {
        for (Throwable c = t; c != null; c = (c.getCause() == c ? null : c.getCause())) {
            String msg = c.getMessage();
            if (msg != null && msg.toLowerCase(Locale.ROOT).contains(constraintName)) {
                return true;
            }
        }
        return false;
    }
}
