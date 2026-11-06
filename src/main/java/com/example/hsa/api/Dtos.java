package com.example.hsa.api;

import com.example.hsa.domain.Account;
import com.example.hsa.domain.Card;
import com.example.hsa.domain.CardStatus;
import com.example.hsa.domain.DeclineReason;
import com.example.hsa.domain.HsaTransaction;
import com.example.hsa.domain.MerchantCategory;
import com.example.hsa.domain.TransactionStatus;
import com.example.hsa.domain.TransactionType;
import com.example.hsa.service.TxResult;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Request and response shapes. Entities are never serialized directly. */
public final class Dtos {

    /** $1,000,000 in cents. A sanity cap on a single request, not a business rule. */
    static final long MAX_AMOUNT_CENTS = 100_000_000L;

    private Dtos() {
    }

    // ---------- requests ----------

    public record CreateAccountRequest(
            @NotBlank @Size(max = 200) String holderName,
            @NotBlank @Email @Size(max = 320) String email,
            @NotNull @Past LocalDate dateOfBirth) {
    }

    public record DepositRequest(
            @NotNull @Positive @Max(MAX_AMOUNT_CENTS) Long amountCents) {
    }

    public record PurchaseRequest(
            @NotNull @Positive @Max(MAX_AMOUNT_CENTS) Long amountCents,
            @NotBlank @Size(max = 200) String merchantName,
            @NotNull MerchantCategory merchantCategory) {
    }

    // ---------- responses ----------

    public record AccountResponse(long id, String holderName, String email, LocalDate dateOfBirth,
                                  long balanceCents, Instant createdAt) {
        static AccountResponse of(Account a) {
            return new AccountResponse(a.id, a.holderName, a.email, a.dateOfBirth, a.balanceCents, a.createdAt);
        }
    }

    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("MM/yy");

    public record CardResponse(long id, long accountId, String maskedNumber, String last4, String expiry,
                               CardStatus status, Instant createdAt) {
        static CardResponse of(Card c) {
            return new CardResponse(c.id, c.accountId, "**** **** **** " + c.last4, c.last4,
                    EXPIRY.format(c.expiry), c.status, c.createdAt);
        }
    }

    public record TransactionResponse(long id, long accountId, Long cardId, TransactionType type,
                                      long amountCents, String merchantName, MerchantCategory merchantCategory,
                                      TransactionStatus status, DeclineReason declineReason, Instant createdAt) {
        static TransactionResponse of(HsaTransaction t) {
            return new TransactionResponse(t.id, t.accountId, t.cardId, t.type, t.amountCents, t.merchantName,
                    t.merchantCategory, t.status, t.declineReason, t.createdAt);
        }
    }

    public record TxResultResponse(TransactionResponse transaction, long balanceAfterCents) {
        static TxResultResponse of(TxResult r) {
            return new TxResultResponse(TransactionResponse.of(r.tx()), r.balanceAfterCents());
        }
    }

    public record CategoryResponse(MerchantCategory category, boolean qualified) {
    }

    public record ApiError(String code, String message) {
    }
}
