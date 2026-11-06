package com.example.hsa.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** A virtual debit card. Only the last four digits are ever stored. */
@Entity
@Table(name = "card")
public class Card extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** Plain id rather than a @ManyToOne: keeps queries explicit and avoids lazy-loading surprises. */
    @Column(name = "account_id", nullable = false)
    public Long accountId;

    @Column(nullable = false, length = 4)
    public String last4;

    /** Last day of the expiry month. */
    @Column(nullable = false)
    public LocalDate expiry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public CardStatus status;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public boolean isUsableOn(LocalDate today) {
        return status == CardStatus.ACTIVE && !today.isAfter(expiry);
    }

    public static List<Card> forAccount(long accountId) {
        return list("accountId = ?1 order by id desc", accountId);
    }

    public static Optional<Card> activeForAccount(long accountId) {
        return find("accountId = ?1 and status = ?2", accountId, CardStatus.ACTIVE).firstResultOptional();
    }
}
