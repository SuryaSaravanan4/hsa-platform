package com.example.hsa.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "account")
public class Account extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "holder_name", nullable = false)
    public String holderName;

    /** Unique (uq_account_email in V1__init.sql). */
    @Column(nullable = false)
    public String email;

    @Column(name = "date_of_birth", nullable = false)
    public LocalDate dateOfBirth;

    /**
     * Cached running balance in cents. Only ever changed by the atomic UPDATE statements in
     * {@link com.example.hsa.service.LedgerRepository}. updatable = false guarantees Hibernate never
     * writes a stale in-memory value back over a concurrent debit, even if an Account is modified.
     */
    @Column(name = "balance_cents", nullable = false, updatable = false)
    public long balanceCents;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
