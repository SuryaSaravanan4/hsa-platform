# Architecture

## Overview

One Quarkus application in front of one MySQL database. There are no other services.

```
Browser (index.html, plain JS)          curl / tests (RestAssured)
              \                          /
               v                        v
   +-----------------------------------------------+
   | Quarkus app                                    |
   |                                                |
   |  api/      AccountResource, CardResource,      |  HTTP + JSON, validation,
   |            CategoryResource, ErrorMappers      |  status codes, DTO mapping
   |               |                                |
   |  service/  AccountService, CardService,        |  business rules,
   |            PurchaseService, LedgerRepository   |  @Transactional boundaries
   |               |                                |
   |  domain/   Account, Card, HsaTransaction,      |  Panache entities, enums,
   |            QualifiedExpensePolicy              |  category rules
   +-----------------------------------------------+
                         |  JDBC (Agroal pool, max 30)
                         v
               MySQL 8.4, InnoDB  (schema owned by Flyway V1__init.sql)
```

- **api** turns HTTP into service calls. It validates input with Bean Validation on request records
  (`Dtos.CreateAccountRequest`, `DepositRequest`, `PurchaseRequest`), maps entities to response
  records so entities are never serialized, and maps errors to `{code, message}` JSON
  (`ErrorMappers`).
- **service** holds the rules and the transaction boundaries. `LedgerRepository` is the only class
  that changes `account.balance_cents`.
- **domain** holds the entities and `QualifiedExpensePolicy`, the single place that decides which
  merchant categories qualify.
- **UI**: `src/main/resources/META-INF/resources/index.html`, a static page served by Quarkus. It uses
  the same public REST endpoints as curl, so there is no UI-only logic.

The app keeps no state in memory. Every rule that matters under concurrency is enforced by MySQL, so
running several app instances against the same database stays correct.

## Data model

All money is stored as `BIGINT` cents and handled as `long` in Java, never as floating point.

```
account 1 ──< card
   1 \
      \──< hsa_transaction >── 0..1 card
```

| Table | Columns | Notes |
|---|---|---|
| `account` | `id`, `holder_name`, `email` (unique), `date_of_birth`, `balance_cents`, `created_at` | `balance_cents` is a cached running total so a debit can be one atomic `UPDATE`. `CHECK (balance_cents >= 0)` is a safety net. |
| `card` | `id`, `account_id`, `last4`, `expiry`, `status` (`ACTIVE`/`FROZEN`), `created_at`, generated `active_account_id` | Only the last four digits exist. "One active card per account" is a unique index on a stored generated column that equals `account_id` when `ACTIVE` and `NULL` otherwise (MySQL has no partial indexes). |
| `hsa_transaction` | `id`, `account_id`, `card_id` (null for deposits), `type` (`DEPOSIT`/`PURCHASE`), `amount_cents`, `merchant_name`, `merchant_category`, `status` (`APPROVED`/`DECLINED`), `decline_reason`, `idempotency_key` (unique, nullable), `created_at` | Audit trail of every attempt, including declines. Named `hsa_transaction` because `TRANSACTION` is a MySQL keyword. A `CHECK` ties `decline_reason` to `status`. |

Entities reference each other by id (`Card.accountId`, `HsaTransaction.cardId`) rather than with
`@ManyToOne`. That keeps every query explicit and avoids lazy-loading surprises. Foreign keys are
still enforced in the database.

Invariant, checked by the concurrency tests: for every account, approved deposits minus approved
purchases equals `balance_cents`.

## Request flow: a card purchase

`POST /api/cards/{id}/purchases` with `{amountCents, merchantName, merchantCategory}` and an optional
`Idempotency-Key` header.

1. `CardResource.purchase` validates the body (amount 1 to 100,000,000 cents, merchant name present,
   known category). Bad input returns 400 before any database work.
2. `PurchaseService.purchase` runs in one database transaction (`@Transactional` on a public method):
   1. If an idempotency key is given and already exists, return the stored transaction (HTTP 200,
      `replayed: true`). The same key with a different amount or card returns 409.
   2. Load the card with `SELECT ... FOR SHARE` (`LockModeType.PESSIMISTIC_READ`). Unknown card
      returns 404.
   3. Card not `ACTIVE`, or past its expiry: decline `CARD_INACTIVE`.
   4. `QualifiedExpensePolicy.isQualified(category)` is false: decline `NOT_QUALIFIED`.
   5. `LedgerRepository.tryDebit(accountId, amount)` returns false: decline `INSUFFICIENT_FUNDS`.
   6. Otherwise approve.
   7. Insert one `hsa_transaction` row with the outcome, then commit. The debit and the row commit
      or roll back together.
3. The response is HTTP 201 with the transaction and `balanceAfterCents`, approved or declined. A
   decline is a normal business outcome, so it is not an HTTP error.

Deposits (`AccountService.deposit`) follow the same shape: optional idempotency check, an atomic
`LedgerRepository.credit`, and one `DEPOSIT` row in the same transaction.

## Concurrency

### The debit

`LedgerRepository.tryDebit` is a single conditional statement:

```sql
UPDATE account
   SET balance_cents = balance_cents - :amount
 WHERE id = :accountId AND balance_cents >= :amount
```

One row updated means approve; zero rows means `INSUFFICIENT_FUNDS`. InnoDB takes an exclusive lock on
the account row for the `UPDATE` and evaluates the `WHERE` against the latest committed balance. With a
$100 balance and $80 and $50 purchases arriving together, one `UPDATE` runs first and leaves $20 or $50.
The second waits for the first to commit, then its `WHERE` no longer matches, so it changes nothing.
The balance cannot go negative and no update is lost.

`tryDebit` is annotated `@Transactional(TxType.MANDATORY)`: it fails if called outside a transaction,
so the balance change can never commit separately from its `hsa_transaction` row.

The code never loads the balance into Java, compares, and saves. That read-check-write pattern lets
two concurrent purchases both read $100 and both succeed.

### The card lock

Between "card is ACTIVE" and the debit, a concurrent freeze could commit. `PurchaseService` reads the
card with `FOR SHARE`. Concurrent purchases on one card share that lock and don't block each other,
but a freeze (an exclusive lock on the card row) waits for in-flight purchases to commit, and a
purchase that arrives during an uncommitted freeze waits for it and then sees `FROZEN`.

Lock order is always card, then account. Freeze locks only the card, and deposits lock only the
account, so no path takes the two locks in the opposite order.

### Idempotency races

`idempotency_key` has a unique index. If two requests with the same new key race, both pass the
lookup, but the second insert violates the index. Its whole transaction rolls back, including its
debit, and `CardResource.purchase` (or `AccountResource.deposit`) retries once. The retry finds the
committed row and replays it.

### Alternatives considered

| Approach | How it works | Verdict |
|---|---|---|
| **Conditional `UPDATE`** (chosen) | Database checks and subtracts in one statement | Simplest correct option. No retry loop, shortest lock hold, works across app instances. |
| Pessimistic lock | `SELECT ... FOR UPDATE` on the account, check in Java, then `UPDATE` | Also correct. Holds the row lock across a round trip and spreads the rule across Java and SQL. |
| Optimistic lock | `@Version` column; the losing writer gets an `OptimisticLockException` and retries | Correct with a retry loop. Under contention (the exact case here) most attempts fail and retry, and the retry code is easy to get wrong. |
| JVM lock | `synchronized` or a `ReentrantLock` around the purchase | Wrong. It only serializes threads in one JVM; a second app instance, or anything else writing to the database, bypasses it. |

### How it is verified

- `ConcurrentPurchaseTest` releases many HTTP requests at once with a `CountDownLatch` and checks the
  outcome against the real database:
  - `eightyAndFiftyAtOnceExactlyOneApproved` (20 repetitions): the assignment's exact case.
  - `twentyIdenticalPurchasesExactlyOneApproved` (10): 20 purchases of $80 against $100.
  - `twentyMixedPurchasesNeverOverspend` (10): 20 mixed $80 and $50 purchases. Two $50 purchases fit
    exactly in $100, so the valid outcomes are {$80}, {$50} or {$50, $50}. The test checks for no
    overspend and that every declined amount is larger than the final balance.
  - Deposit races, and deposits racing purchases.
  - Each test also checks that the ledger sum equals the cached balance and that every attempt has a row.
- `RaceConditionTest` covers a purchase during an uncommitted freeze, and 10 requests with the same
  idempotency key.
- Mutation checks: with `tryDebit` replaced by a naive read-check-write, 45 of the 46 tests in
  `ConcurrentPurchaseTest` failed. With the `FOR SHARE` lock removed, the freeze test failed 3 of 3
  times. Both changes were reverted.

## Design tradeoffs

- **Cached balance instead of a pure ledger.** Summing transactions on every purchase would avoid a
  denormalized column, but the atomic conditional `UPDATE` needs one row to lock and test. The
  concurrency tests check that the two never drift apart.
- **Declines are 201, not 4xx.** The network asked us to authorize a purchase and we recorded an
  answer. 4xx is reserved for bad requests (400), unknown ids (404) and conflicts (409).
- **Category rules in code.** `QualifiedExpensePolicy` is an `EnumSet`. That's easy to read and
  change, but a rule change needs a redeploy. A real system would key on network MCC codes from a table.
- **No full card number.** Purchases reference the card id, so nothing needs a PAN. Storing one would
  bring PCI scope with it.
- **Native SQL for the ledger.** `LedgerRepository` uses native queries so the code reads exactly like
  the statement it runs. The rest uses Panache.
- **Flyway owns the schema.** Hibernate never generates DDL, so the reviewer gets the same versioned
  schema in dev, tests and the packaged app.
- **Static HTML instead of a frontend framework.** The UI isn't the focus, and a single file needs no
  build step.

## Out of scope

- Authentication and authorization (any caller can act on any account).
- IRS annual contribution limits, catch-up contributions, and age rules.
- Refunds, reversals, partial authorizations, and holds that settle later.
- Real MCC codes, a merchant registry, or substantiation of receipts.
- Full card numbers, CVV, PCI concerns, and multiple cards per account at once.
- Pagination of transaction history, rate limiting, observability, and deployment beyond docker-compose.
