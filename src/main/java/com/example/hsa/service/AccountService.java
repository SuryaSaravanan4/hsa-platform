package com.example.hsa.service;

import com.example.hsa.domain.Account;
import com.example.hsa.domain.HsaTransaction;
import com.example.hsa.domain.TransactionStatus;
import com.example.hsa.domain.TransactionType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@ApplicationScoped
public class AccountService {

    @Inject
    LedgerRepository ledger;

    @Transactional
    public Account create(String holderName, String email, LocalDate dateOfBirth) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        // Uniqueness is enforced by the uq_account_email index; a duplicate becomes a 409 in ErrorMappers.
        Account a = new Account();
        a.holderName = holderName.trim();
        a.email = normalizedEmail;
        a.dateOfBirth = dateOfBirth;
        a.balanceCents = 0;
        a.createdAt = Instant.now();
        a.persistAndFlush();
        return a;
    }

    public List<Account> listAll() {
        return Account.list("order by id desc");
    }

    public Account get(long id) {
        return Account.<Account>findByIdOptional(id).orElseThrow(() -> ApiException.notFound("Account", id));
    }

    /** Deposits funds: an atomic credit plus one DEPOSIT row, in one transaction. */
    @Transactional
    public TxResult deposit(long accountId, long amountCents) {
        if (!ledger.credit(accountId, amountCents)) {
            throw ApiException.notFound("Account", accountId);
        }
        HsaTransaction tx = new HsaTransaction();
        tx.accountId = accountId;
        tx.type = TransactionType.DEPOSIT;
        tx.amountCents = amountCents;
        tx.status = TransactionStatus.APPROVED;
        tx.createdAt = Instant.now();
        tx.persistAndFlush();
        return new TxResult(tx, ledger.balanceOf(accountId));
    }

    public List<HsaTransaction> transactions(long accountId) {
        get(accountId);
        return HsaTransaction.forAccount(accountId);
    }
}
