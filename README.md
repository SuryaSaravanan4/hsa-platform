# HSA Platform (draft)

A small Health Savings Account app: create an account, deposit funds, get a virtual debit card, and
make purchases. Purchases are approved only for qualified medical merchant categories, and concurrent
purchases can never overspend the balance.

> Draft README with run steps only. To be rewritten.

## Prerequisites

- Java 21 (`java -version` must say 21; set `JAVA_HOME` if another JDK is the default)
- Docker running (Dev Services and docker-compose both use it)
- No Maven install needed; use the wrapper (`./mvnw`, or `mvnw.cmd` on Windows cmd/PowerShell)

## Run (dev mode, simplest)

```bash
./mvnw quarkus:dev
```

Quarkus Dev Services starts a throwaway MySQL 8.4 container and Flyway creates the schema.
Open http://localhost:8080.

## Run (packaged, against docker-compose MySQL)

```bash
docker compose up -d --wait
./mvnw package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```

Override the DB with `HSA_DB_URL`, `HSA_DB_USER`, `HSA_DB_PASSWORD`.

## Tests

```bash
./mvnw test
```

Tests run over HTTP against a real MySQL container (Dev Services). `ConcurrentPurchaseTest` covers the
$100 / $80 + $50 race.

## Concurrency demo

In the UI, press **Fire $80 + $50 at once**. It creates an account with $100 and a card, then sends both
purchases simultaneously. Exactly one is approved; the other is declined with `INSUFFICIENT_FUNDS`.

## API

| Method | Path | Body |
|---|---|---|
| POST | `/api/accounts` | `{holderName, email, dateOfBirth}` |
| GET | `/api/accounts`, `/api/accounts/{id}` | |
| POST | `/api/accounts/{id}/deposits` | `{amountCents}` (optional `Idempotency-Key` header) |
| POST | `/api/accounts/{id}/cards` | none |
| GET | `/api/accounts/{id}/cards`, `/api/accounts/{id}/transactions` | |
| POST | `/api/cards/{id}/freeze`, `/api/cards/{id}/unfreeze` | none |
| POST | `/api/cards/{id}/purchases` | `{amountCents, merchantName, merchantCategory}` (optional `Idempotency-Key`) |
| GET | `/api/categories` | |

Example race with curl (after creating account 1 with $100 and card 1):

```bash
curl -s -X POST localhost:8080/api/cards/1/purchases -H 'Content-Type: application/json' -d '{"amountCents":8000,"merchantName":"A","merchantCategory":"PHARMACY"}' & curl -s -X POST localhost:8080/api/cards/1/purchases -H 'Content-Type: application/json' -d '{"amountCents":5000,"merchantName":"B","merchantCategory":"PHARMACY"}'; wait
```
