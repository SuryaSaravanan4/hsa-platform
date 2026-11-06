package com.example.hsa.service;

import com.example.hsa.domain.Account;
import com.example.hsa.domain.Card;
import com.example.hsa.domain.CardStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

@ApplicationScoped
public class CardService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Issues a virtual card. Only the last four digits exist anywhere: no full card number is
     * generated or stored, since nothing in this system needs one (purchases reference the card id).
     * At most one ACTIVE card per account; the database enforces this with a unique index too.
     */
    @Transactional
    public Card issue(long accountId) {
        if (Account.findById(accountId) == null) {
            throw ApiException.notFound("Account", accountId);
        }
        if (Card.activeForAccount(accountId).isPresent()) {
            throw ApiException.conflict("CARD_ALREADY_ACTIVE",
                    "Account already has an active card; freeze it before issuing a new one");
        }
        Card c = new Card();
        c.accountId = accountId;
        c.last4 = String.format("%04d", RANDOM.nextInt(10_000));
        c.expiry = YearMonth.now(ZoneOffset.UTC).plusYears(3).atEndOfMonth();
        c.status = CardStatus.ACTIVE;
        c.createdAt = Instant.now();
        c.persistAndFlush();
        return c;
    }

    public List<Card> forAccount(long accountId) {
        if (Account.findById(accountId) == null) {
            throw ApiException.notFound("Account", accountId);
        }
        return Card.forAccount(accountId);
    }

    @Transactional
    public Card setStatus(long cardId, CardStatus status) {
        Card c = Card.findById(cardId);
        if (c == null) {
            throw ApiException.notFound("Card", cardId);
        }
        if (status == CardStatus.ACTIVE && c.status != CardStatus.ACTIVE
                && Card.activeForAccount(c.accountId).isPresent()) {
            throw ApiException.conflict("CARD_ALREADY_ACTIVE", "Account already has another active card");
        }
        c.status = status;
        c.persistAndFlush();
        return c;
    }
}
