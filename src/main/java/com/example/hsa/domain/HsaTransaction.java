package com.example.hsa.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

/** Audit trail of every money movement attempt, including declined purchases. */
@Entity
@Table(name = "hsa_transaction")
public class HsaTransaction extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "account_id", nullable = false)
    public Long accountId;

    @Column(name = "card_id")
    public Long cardId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public TransactionType type;

    @Column(name = "amount_cents", nullable = false)
    public long amountCents;

    @Column(name = "merchant_name")
    public String merchantName;

    @Enumerated(EnumType.STRING)
    @Column(name = "merchant_category")
    public MerchantCategory merchantCategory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public TransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "decline_reason")
    public DeclineReason declineReason;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public static List<HsaTransaction> forAccount(long accountId) {
        return list("accountId = ?1 order by id desc", accountId);
    }
}
