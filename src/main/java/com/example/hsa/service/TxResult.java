package com.example.hsa.service;

import com.example.hsa.domain.HsaTransaction;

/** A recorded transaction plus the account balance right after it. */
public record TxResult(HsaTransaction tx, long balanceAfterCents) {
}
