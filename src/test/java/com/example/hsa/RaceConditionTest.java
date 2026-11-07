package com.example.hsa;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.RepeatedTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Races other than "two purchases vs one balance" (those live in ConcurrentPurchaseTest). */
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
}
