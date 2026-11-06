package com.example.hsa.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

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

    @Column(nullable = false, unique = true)
    public String email;

    @Column(name = "date_of_birth", nullable = false)
    public LocalDate dateOfBirth;

    /**
     * Cached running balance in cents. Only ever changed by the atomic UPDATE statements in
     * {@link com.example.hsa.service.LedgerRepository}; never assign this field and save it.
     */
    @Column(name = "balance_cents", nullable = false)
    public long balanceCents;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
