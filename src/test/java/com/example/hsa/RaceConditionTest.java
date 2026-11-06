package com.example.hsa;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/** Races other than "two purchases vs one balance" (that one lives in ConcurrentPurchaseTest). */
@QuarkusTest
class RaceConditionTest {

    @Inject
    EntityManager em;

    /**
     * Justifies the {@code PESSIMISTIC_READ} (SELECT ... FOR SHARE) card lock in PurchaseService.
     *
     * <p>Another transaction freezes the card and holds its lock, uncommitted, for 1.5 seconds. A
     * purchase sent meanwhile must WAIT for that transaction and then see the card as FROZEN.
     * Without the lock, MySQL's plain consistent read would return the old ACTIVE row straight
     * away and the purchase would be approved on a card that is being frozen.
     */
    @RepeatedTest(3)
    void purchaseWaitsForInFlightFreezeAndIsDeclined() throws Exception {
        long[] ac = Api.fundedAccountWithCard(10_000);
        long cardId = ac[1];

        CountDownLatch frozenButUncommitted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> freezer = pool.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                em.createNativeQuery("UPDATE card SET status = 'FROZEN' WHERE id = :id")
                        .setParameter("id", cardId).executeUpdate();
                frozenButUncommitted.countDown();
                try {
                    Thread.sleep(1_500); // hold the row lock, uncommitted
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(frozenButUncommitted.await(10, TimeUnit.SECONDS));

            long start = System.nanoTime();
            Api.purchase(cardId, 1_000, "PHARMACY").statusCode(201)
                    .body("transaction.status", org.hamcrest.Matchers.equalTo("DECLINED"))
                    .body("transaction.declineReason", org.hamcrest.Matchers.equalTo("CARD_INACTIVE"));
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            freezer.get(10, TimeUnit.SECONDS);
            assertTrue(waitedMs >= 1_000, "purchase should have blocked on the freeze, waited only " + waitedMs + "ms");
            assertEquals(10_000, Api.balance(ac[0]));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 10 retries of the same purchase (same Idempotency-Key) arrive at once. Exactly one is
     * created (201), the rest replay it (200), the account is charged once, and one row exists.
     * Exercises the unique-index + rollback + retry path in CardResource.purchase.
     */
    @RepeatedTest(5)
    void sameIdempotencyKeyRacingChargesOnce() throws Exception {
        long[] ac = Api.fundedAccountWithCard(10_000);
        String key = "race-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("amountCents", 3_000, "merchantName", "CVS", "merchantCategory", "PHARMACY");

        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return given().contentType(ContentType.JSON).header("Idempotency-Key", key).body(body)
                            .post("/api/cards/{id}/purchases", ac[1]);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();

            int created = 0;
            int replayed = 0;
            Long txId = null;
            for (Future<Response> f : futures) {
                Response r = f.get(60, TimeUnit.SECONDS);
                int status = r.statusCode();
                assertTrue(status == 201 || status == 200, "unexpected status " + status + ": " + r.asString());
                if (status == 201) created++; else replayed++;
                long id = r.jsonPath().getLong("transaction.id");
                if (txId == null) txId = id;
                assertEquals(txId, id, "every response must describe the same transaction");
            }
            assertEquals(1, created);
            assertEquals(n - 1, replayed);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(7_000, Api.balance(ac[0]));
        given().get("/api/accounts/{id}/transactions", ac[0]).then().statusCode(200)
                .body("size()", org.hamcrest.Matchers.equalTo(2)); // deposit + one purchase
    }
}
