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
import java.util.Optional;

@ApplicationScoped
public class AccountService {

    @Inject
    LedgerRepository ledger;

    @Transactional
    public Account create(String holderName, String email, LocalDate dateOfBirth) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        // Friendly pre-check. The unique constraint is what actually guarantees uniqueness
        // (a concurrent duplicate is caught and mapped to 409 in the resource).
        if (Account.count("email", normalizedEmail) > 0) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with that email already exists");
        }
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

    /**
     * Deposits funds. If the idempotency key was already used, returns the original transaction
     * instead of depositing twice.
     */
    @Transactional
    public TxResult deposit(long accountId, long amountCents, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<HsaTransaction> existing = HsaTransaction.byIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                HsaTransaction prior = existing.get();
                Idempotency.requireSameRequest(prior, accountId, null, amountCents);
                return TxResult.replay(prior, ledger.balanceOf(prior.accountId));
            }
        }
        if (!ledger.credit(accountId, amountCents)) {
            throw ApiException.notFound("Account", accountId);
        }
        HsaTransaction tx = new HsaTransaction();
        tx.accountId = accountId;
        tx.type = TransactionType.DEPOSIT;
        tx.amountCents = amountCents;
        tx.status = TransactionStatus.APPROVED;
        tx.idempotencyKey = idempotencyKey;
        tx.createdAt = Instant.now();
        tx.persistAndFlush();
        return TxResult.fresh(tx, ledger.balanceOf(accountId));
    }

    public List<HsaTransaction> transactions(long accountId) {
        get(accountId);
        return HsaTransaction.forAccount(accountId);
    }
}
