# AI usage

## Tools

- **Claude (chat, in a claude.ai project).** Used for planning before any code: a written game plan
  covering the stack (Java, Quarkus and MySQL, with no S3 and no authentication), the three-table data
  model, the conditional `UPDATE` approach to concurrency, a test plan, and a list of mistakes AI tends to make on this kind of task. Later, a separate Claude
  conversation acted as a senior reviewer of the finished code. Its verdict was that the architecture
  was sound with nothing that had to be fixed; I had its three small suggestions applied (locale-safe
  lowercasing of constraint names, and two clarifications in `architecture.md` about lock ordering and
  when the decision log line is written). I did not accept the lock-ordering suggestion as written: it
  said card issue never reverses the card-then-account order, but checked against the code, card issue
  takes a shared lock on the account first. The wording in `architecture.md` was corrected to say that,
  and why it still cannot deadlock (both of those locks are shared).
- **Claude Code** (Anthropic's coding agent), running on my Windows machine. It scaffolded the Quarkus
  project, wrote the code, tests and docs, ran the Maven build and the test suite against MySQL in
  Docker, ran the app, and checked the UI in a browser.

No other AI tools were used.

## Which parts AI helped with

Claude Code wrote the first version of all the code: the Flyway schema, entities, services, REST
resources, the single-page UI, the tests, the README and the architecture doc. The design decisions came
from the plan and were handed to it as fixed requirements, not left for it to choose:

- money as `BIGINT` cents, never floating point;
- three tables, with every declined purchase recorded with a reason;
- the debit as one conditional `UPDATE ... WHERE balance_cents >= :amount` inside a public
  `@Transactional` method, and explicitly no read-then-check-then-save in Java;
- the category rules in one small class;
- tests through HTTP against a real MySQL (Quarkus Dev Services), including a `CountDownLatch`
  concurrency test;
- small commits, and `./mvnw test` green before every commit.

The rule against read-then-check-then-save came from me, before any code existed, from my experience
at CoverMyMeds. Loading a balance, checking it in application code and saving it back is a race: two
concurrent purchases both read $100, both pass the check, and the balance is either overspent or one
update is silently lost. So I required the check and the subtraction to happen in one database
statement. That is `LedgerRepository.tryDebit`: one row updated means approved, zero rows means
declined with `INSUFFICIENT_FUNDS`, and MySQL's row lock makes it atomic. `tryDebit` and `credit` are the
only statements that change `balance_cents` (both `@Transactional(MANDATORY)`), `Account.balanceCents`
is mapped `updatable = false` so Hibernate never writes it, and `V1__init.sql` has
`CHECK (balance_cents >= 0)` as a last line of defense.

The first full draft was generated in one unattended Claude Code run from a written brief, while I was
studying for an exam. It was then reviewed and simplified in later passes (below).

## How I reviewed the AI's work

I treated everything the AI produced as a pull request from a fast, confident teammate: likely to look
right, possibly subtly wrong, and possibly doing more than was asked. My review followed these steps:

1. **Requirements first, not the code.** I re-read the assignment and listed what had to be true:
   accounts, deposits, one virtual card, qualified categories only, every decline recorded with a
   reason, and $80 and $50 against $100 at the same time ending with exactly one approval and never a
   negative balance.
2. **Run it before reading it.** I ran `./mvnw test`, then the app, and clicked through every flow:
   deposit, card issue, an approved pharmacy purchase, a declined restaurant purchase, an overspend, a
   purchase on a frozen card, and the $80/$50 demo several times at $100 and at other starting balances
   ($30, $65, $150). Seeing that a fixed $100 always makes
   the two purchases contend is why I asked for a starting-balance input, so a reviewer can also see the
   outcomes where nothing races.
3. **Trace one request end to end.** I followed a purchase from `CardResource.purchase` to
   `PurchaseService.purchase` to `LedgerRepository.tryDebit` and the transaction row, and checked every
   place the balance can change: only the two `UPDATE` statements in `LedgerRepository`.
4. **Read the money-critical code line by line.** `tryDebit`, `PurchaseService.purchase` (the order of
   checks, the `FOR SHARE` card lock, `@Transactional` on a public method) and `V1__init.sql` (cents as
   `BIGINT`, the `CHECK` constraints, the unique indexes), plus the safety nets behind the debit:
   `TxType.MANDATORY`, `updatable = false` and `CHECK (balance_cents >= 0)`.
5. **Review the tests against the requirements, not the code.** For each test I asked whether it would
   fail if the code were wrong. That is how the mixed $80/$50 test stands out: it has to allow two $50
   approvals, because both fit in $100.
6. **Prove the tests, don't trust them.** I reran the debit mutation check myself: I temporarily
   replaced the conditional `UPDATE` with a read-then-save version, watched `ConcurrentPurchaseTest`
   fail, and reverted it.
7. **Look for scope creep and invented APIs.** That is how the idempotency keys (not in the assignment
   and flawed) and a non-existent annotation (`@NotNullBody`) were caught.
8. **Ask for evidence, not assertions.** When Claude Code added a lock nobody asked for, the rule was
   "prove it or remove it"; it stayed only once a test showed the bug it prevents.
9. **Use an independent second reviewer, and check the reviewer too.** A separate Claude session
   reviewed the architecture. I applied its small suggestions, but checked each against the code first,
   and corrected the one that was wrong (lock ordering).
10. **Verify AI explanations against the code.** When I asked Claude to explain something, I checked it
    in the code. For example, I first assumed a naive read-then-save would leave the balance at -$30;
    walking through it showed it usually loses an update instead and leaves a wrong positive balance,
    which is harder to notice.
11. **Small commits, and nothing I can't explain.** Every change was one small commit with the tests
    green, so each diff could be reviewed on its own.

I also reviewed the build file and imports and asked for the tidy-up pass described below, and I set up
the private GitHub repository; my working notes and study guides are not in it or in its history. Fresh
clones that follow only the README were also checked (see the verification section).

## Representative prompts and workflows

**1. Plan first, then a written brief.** Instead of asking for "an HSA app", the plan was turned into
a brief with explicit constraints. An excerpt of the brief for the overnight build:

> "debit with a single conditional statement inside @Transactional (public method): UPDATE account SET
> balance_cents = balance_cents - :amt WHERE id = :id AND balance_cents >= :amt; 1 row → approve,
> 0 rows → decline INSUFFICIENT_FUNDS. Do NOT read-then-check-then-save in Java."

It also required a running notes file listing what was built, the decisions made, and anything
unverified. That file made the draft reviewable instead of a black box.

**2. Prove it or remove it.** Claude Code added a `SELECT ... FOR SHARE` lock on the card that nobody
asked for, and flagged it as unverified. The follow-up brief said:

> "review the card FOR SHARE lock you added. Either verify the SQL it generates and justify it with a
> test, or remove it."

It was kept only after the SQL log showed `for share` and a new test showed it prevents a real bug (see
verification).

**3. A simplification brief with explicit keep and remove lists.** After the review, I asked for the
smallest correct solution:

> "For every class, method and test, ask 'does the assignment or a real correctness risk need this?'
> Remove what doesn't."

The brief named what to remove (idempotency keys, the string-matching error helper) and what must not
change (the conditional `UPDATE`, `TxType.MANDATORY`, the CHECK constraints, the card lock and its test,
the ledger-equals-balance assertions, the $80/$50 concurrency tests). It also required a green test run
before each commit. The result was a series of small commits, each one reviewable on its own. The
brief also asked for an injected `java.time.Clock` with a test for the expired-card decline, and one
structured `purchase_decision` log line per purchase. During the same pass Claude Code also mapped
`Account.balanceCents` as `updatable = false`, so Hibernate can never write a stale balance.

**4. Cleaning up for the next reader, requested by me.** Once the code worked, I asked for it to be
made easier for the next developer to read and maintain, without changing behavior. That came in two
steps.

- **Simplification (item 3).** Anything the assignment did not need was removed: the idempotency keys,
  the helper that matched constraint names inside exception messages (replaced by Hibernate's
  `ConstraintViolationException.getConstraintName()`), and the service-level duplicate checks that
  repeated the database's unique indexes and could race. The database constraints are now the single
  source of truth, and the core logic was kept as is. This pass cut the main Java code from 919 to 818
  lines.
- **Tidy-up of the build file and imports (commit `1ba0d14`).** I reviewed what the project imports and
  depends on, Claude Code first reported what could go, and I approved the changes: it removed the
  Maven failsafe plugin, the `native` profile and the `skipITs` property, which come from the Quarkus
  project template and are unused here (there are no `*IT` classes and no native build); it removed the
  explicit `quarkus-arc` dependency, which the REST and Hibernate extensions already provide; and it
  replaced every wildcard import (`jakarta.ws.rs.*`, `jakarta.persistence.*`, and the static Hamcrest
  and JUnit `*` imports) with explicit ones. The compiler caught one name the first pass missed
  (`matchesPattern` in `CardTest`), which was then added.

The reason for both: less code and no unused configuration means fewer things for the next reader to
wonder about, and explicit imports show exactly where each class comes from. The explicit imports added
some lines back (the main Java code is 848 lines now), which I think is the right trade for clarity. No
application logic changed in the tidy-up: all 70 tests passed and `mvnw package -DskipTests` still built
`quarkus-run.jar` afterwards.

**5. UI changes I asked for, frontend only.** Two requests, both limited to `index.html` with no change
to the Java code, the API or the schema:

- a **Starting balance** input for the concurrency demo (default $100), with a note that says what to
  expect: under $50 both declined, $50 to $79.99 only the $50, $80 to $129.99 exactly one (the locking
  case), $130 or more both approved;
- a polish pass so a reviewer can evaluate the core flows easily: one **Active account** selector
  instead of copying ids, balance and history refreshing after every action, inline approved and
  declined messages instead of browser pop-ups, API errors shown as plain sentences, and purchases on a
  frozen card sent to the API so the `CARD_INACTIVE` decline can actually be demonstrated.

## Where AI output was wrong, and how it was corrected

1. **A concurrency test that expected the wrong answer.** The plan said to fire 20 simultaneous $80 and
   $50 purchases at a $100 balance and assert exactly one approval. That is false: two $50 purchases fit
   exactly into $100. The first test run "failed" 5 of 10 repetitions with two approvals. The code was
   right and the assertion was wrong. It was split into three tests:
   - the assignment's exact case, $80 and $50 at once: exactly one approved;
   - 20 purchases of $80: exactly one approved;
   - 20 mixed purchases: the approved set must be {$80}, {$50} or {$50, $50}, with no overspend and no
     declined purchase that would still have fit.

2. **A flawed feature nobody asked for.** The first draft added idempotency keys for retried requests.
   The review found that the purchase replay check compared only the amount and the card, passed the
   stored transaction's own account id to itself (so the account comparison could never fail), and made
   keys unique across all accounts instead of per account. The assignment doesn't require idempotency, so
   the simplification pass removed it instead of patching it.

3. **Error handling by string matching.** Unique-constraint errors were detected by searching exception
   message text for constraint names. The review flagged this as fragile. It now uses Hibernate's
   `ConstraintViolationException.getConstraintName()`. The racy service-level duplicate checks were
   removed too, so the database's unique indexes are the only source of truth.

Smaller ones, caught by the compiler, the first test run or screenshots: an annotation that doesn't
exist (`@NotNullBody`); body-less `POST` endpoints returning HTTP 415 because of a class-level
`@Consumes(JSON)`; amounts sent as `12.5` or `"100"` being silently truncated or converted instead of
rejected (an independent grading review caught this; the API now rejects non-integer amounts with 400,
with tests for both deposits and purchases); and, in the first version of the UI polish, a status color that leaked onto the card
graphic and result messages that stayed on screen after switching accounts. Both UI issues were seen
in the screenshots and fixed before that change was committed.

## How the final implementation was verified

- **76 automated tests**, all through HTTP against real MySQL 8.4 in Docker, with no mocks: the
  account, deposit, card and purchase paths; every decline reason, including an expired card using an
  injected test clock; validation and 404/409 errors, including non-integer amounts; and the
  concurrency tests.
- **Repeated runs.** The suite ran after every change, and several times back to back after the
  simplification pass (3 consecutive full runs with no failures). It passed again after the UI changes
  and after the tidy-up.
- **Mutation check on the debit.** As a deliberate test of the tests, `tryDebit` was temporarily
  replaced with a naive version (read the balance, compare in Java, write the new value), the exact
  race the design rules out. 45 of the 46 tests in `ConcurrentPurchaseTest` failed, with both $80 and
  $50 approved, and `assertLedgerMatchesBalance` flagged that the approved transactions no longer
  matched the stored balance. So the tests detect the race and are not passing by luck. The change was
  reverted.
- **Mutation check on the card lock.** With the `FOR SHARE` lock removed,
  `RaceConditionTest.purchaseWaitsForInFlightFreezeAndIsDeclined` failed 3 of 3 times: a purchase was
  approved on a card that was being frozen. With the lock, the purchase waits and is declined.
- **Fresh clone.** A new clone following only the README (taken before the final UI and tidy-up
  commits): 70 tests passed with `mvnw.cmd`, the app started with `quarkus:dev`, and the
  **Fire $80 + $50 at once** button showed $80 approved, $50 declined `INSUFFICIENT_FUNDS`, and $20 left.
  A second fresh clone, taken after the tidy-up: 70 tests passed twice, the package built, the app
  started, and an API smoke test passed, including the $80/$50 race 10 of 10 with exactly one approval.
- **UI check in a real browser.** After the UI changes, every flow was clicked through in Chrome:
  create account, duplicate email, deposit, issue card, second card, an approved pharmacy purchase, a
  `NOT_QUALIFIED` restaurant purchase, an `INSUFFICIENT_FUNDS` overspend, a `CARD_INACTIVE` purchase on a
  frozen card, and the $80/$50 race at $100 ten times with exactly one approval each time (both the $80
  and the $50 won at least once). Starting balances of $30, $65 and $150 matched their expected
  outcomes.
- **After the tidy-up.** All 70 tests passed again, and `mvnw package -DskipTests` still built
  `quarkus-run.jar`, so removing the unused build configuration and imports changed nothing in behavior.
- **Packaged app.** The jar against the docker-compose MySQL: a duplicate email and a second active card
  returned 409, a concurrent $80/$50 curl race left $20, and each decision appeared as one
  `purchase_decision` log line.

I can explain the core path in my own words. The card and the category are checked before any money
moves, so a purchase declined for its card or category never runs the debit `UPDATE`. An
insufficient-funds decline does run it: the `UPDATE` changes nothing but briefly takes the account's
row lock. The debit is a
single conditional `UPDATE` because reading the balance first would let two purchases both see $100
and both be approved, with one update silently lost. Against $100, exactly one of $80 and $50 wins
because MySQL's row lock makes the second `UPDATE` re-check the new balance. Two safety nets sit behind
it: `updatable = false` means Hibernate never writes the balance after the account is created, and
`CHECK (balance_cents >= 0)` makes MySQL reject and roll back any update that would go negative. I can
also explain what the tests prove: the concurrency tests release all requests at once with a
`CountDownLatch`, the $80/$50 test expects exactly one approval, the 20-purchase mixed test allows two
$50 approvals because both fit in $100, every concurrency test checks that the approved transactions
add up to the stored balance, and the card-freeze test passes only because the purchase waits on the
`FOR SHARE` lock.
