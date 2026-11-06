# HSA Platform

A small Health Savings Account (HSA) web app that runs locally. Users create an account, deposit funds,
get a virtual debit card, and make purchases. A purchase is approved only if the merchant category is a
qualified medical expense (pharmacy, hospital, doctor, dental, vision), and concurrent purchases can
never overspend the balance.

Stack: Java 21, Quarkus 3.40, MySQL 8.4 (InnoDB), Hibernate ORM with Panache, Flyway, Quarkus REST
(Jackson). The UI is a single static HTML page that calls the same REST API.

See [architecture.md](architecture.md) for the design and the concurrency approach.

## Prerequisites

| Tool | Version | Check |
|---|---|---|
| JDK | 21 | `java -version` |
| Docker | running | `docker info` |

You do **not** need Maven installed: use the included wrapper (`./mvnw` on macOS/Linux/Git Bash,
`mvnw.cmd` in Windows PowerShell or cmd).

**Windows note on JDK 21.** The wrapper uses `JAVA_HOME`. If you have several JDKs installed and
`JAVA_HOME` points to an older one, the build fails with `release version 21 not supported`. Point it at
JDK 21 for the current shell (adjust the path to your install):

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.1.12-hotspot"
```

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.1.12-hotspot"
```

## Run the app (one command)

```bash
./mvnw quarkus:dev
```

Then open http://localhost:8080.

With Docker running, Quarkus Dev Services starts a throwaway MySQL 8.4 container automatically and
Flyway creates the schema. The first start pulls the MySQL image and takes a minute; later starts take
about 20 seconds. Data is discarded when you stop dev mode.

### Alternative: packaged app with a persistent MySQL

```bash
docker compose up -d --wait
./mvnw package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```

This uses the MySQL in `docker-compose.yml` (`localhost:3306`, database/user/password `hsa`). Override it
with the `HSA_DB_URL`, `HSA_DB_USER` and `HSA_DB_PASSWORD` environment variables. Stop it with
`docker compose down` (add `-v` to delete the data). If you ran an earlier build of this project
against the same compose volume, run `docker compose down -v` once: the unreleased V1 migration was
edited, so Flyway would reject the old schema's checksum.

## Run the tests

```bash
./mvnw test
```

70 tests run over HTTP against a real MySQL container (Dev Services), including the concurrency tests in
`ConcurrentPurchaseTest` and `RaceConditionTest`. Docker must be running. A full run takes 1 to 2 minutes.

## Demo: concurrent purchases

1. Start the app and open http://localhost:8080.
2. In **5. Concurrency demo**, press **Fire $80 + $50 at once**.
3. The page creates a fresh account with $100 and a card, then sends an $80 and a $50 pharmacy purchase
   at the same time. Exactly one is approved, the other is declined with `INSUFFICIENT_FUNDS`, and the
   final balance is $20 or $50. Both purchases appear in the transaction history.

The rest of the page walks through the normal flow: create an account, deposit, issue a card, simulate a
purchase (pick a qualified or non-qualified category), and freeze or unfreeze the card. The **API log**
at the bottom shows every request and response.

The same race with curl, against an account that has $100 and card id 1:

```bash
curl -s -X POST localhost:8080/api/cards/1/purchases -H 'Content-Type: application/json' -d '{"amountCents":8000,"merchantName":"A","merchantCategory":"PHARMACY"}' & curl -s -X POST localhost:8080/api/cards/1/purchases -H 'Content-Type: application/json' -d '{"amountCents":5000,"merchantName":"B","merchantCategory":"PHARMACY"}'; wait
```

## API

All amounts are integer cents. Errors are JSON `{"code": "...", "message": "..."}`.

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/api/accounts` | `{holderName, email, dateOfBirth}` | 201 |
| GET | `/api/accounts` | | 200 |
| GET | `/api/accounts/{id}` | | 200 |
| POST | `/api/accounts/{id}/deposits` | `{amountCents}` | 201 |
| POST | `/api/accounts/{id}/cards` | none | 201 |
| GET | `/api/accounts/{id}/cards` | | 200 |
| GET | `/api/accounts/{id}/transactions` | | 200 |
| POST | `/api/cards/{id}/freeze` | none | 200 |
| POST | `/api/cards/{id}/unfreeze` | none | 200 |
| POST | `/api/cards/{id}/purchases` | `{amountCents, merchantName, merchantCategory}` | 201 |
| GET | `/api/categories` | | 200 |

A declined purchase is still a recorded transaction, so it returns 201 with `"status": "DECLINED"` and a
`declineReason` (`CARD_INACTIVE`, `NOT_QUALIFIED` or `INSUFFICIENT_FUNDS`). Invalid input returns 400,
unknown ids 404 (`NOT_FOUND`), a duplicate email 409 (`EMAIL_TAKEN`), and issuing or unfreezing a second
active card 409 (`CARD_ALREADY_ACTIVE`).

Every purchase decision is logged as one `key=value` line, for example:

```
purchase_decision txId=25 cardId=5 accountId=9 amountCents=5000 category=PHARMACY outcome=DECLINED declineReason=INSUFFICIENT_FUNDS
```

Merchant categories: `PHARMACY`, `HOSPITAL`, `DOCTOR`, `DENTAL`, `VISION` (qualified) and `RESTAURANT`,
`ELECTRONICS`, `GROCERY`, `CLOTHING` (not qualified).

## Project layout

```
src/main/java/com/example/hsa/
  domain/    entities (Account, Card, HsaTransaction), enums, QualifiedExpensePolicy
  service/   LedgerRepository (balance changes), PurchaseService, AccountService, CardService
  api/       REST resources, request/response records, error mappers
src/main/resources/
  db/migration/V1__init.sql        schema
  META-INF/resources/index.html    UI
src/test/java/com/example/hsa/      API, decline-path and concurrency tests
```
