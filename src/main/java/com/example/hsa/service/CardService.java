package com.example.hsa.service;

import com.example.hsa.domain.Account;
import com.example.hsa.domain.Card;
import com.example.hsa.domain.CardStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

@ApplicationScoped
public class CardService {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    Clock clock;

    /**
     * Issues a virtual card. Only the last four digits exist anywhere: no full card number is
     * generated or stored, since nothing in this system needs one (purchases reference the card id).
     * At most one ACTIVE card per account: enforced by the uq_card_one_active_per_account index,
     * which ErrorMappers turns into a 409.
     */
    @Transactional
    public Card issue(long accountId) {
        if (Account.findById(accountId) == null) {
            throw ApiException.notFound("Account", accountId);
        }
        Card c = new Card();
        c.accountId = accountId;
        c.last4 = String.format("%04d", RANDOM.nextInt(10_000));
        c.expiry = YearMonth.now(clock).plusYears(3).atEndOfMonth();
        c.status = CardStatus.ACTIVE;
        c.createdAt = Instant.now(clock);
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
        c.status = status;
        c.persistAndFlush();
        return c;
    }
}
