package com.example.hsa;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core requirement: balance $100, purchases of $80 and $50 at the same time, exactly one wins
 * and the balance never goes negative. Runs through real HTTP against real MySQL (Dev Services).
 */
@QuarkusTest
class ConcurrentPurchaseTest {

    private static final int THREADS = 20;
    private static final long START_BALANCE = 10_000; // $100.00

    /** The exact scenario from the assignment: $100, then $80 and $50 at the same instant. */
    @RepeatedTest(20)
    void eightyAndFiftyAtOnceExactlyOneApproved() throws Exception {
        long[] ac = Api.fundedAccountWithCard(START_BALANCE);
        List<JsonPath> results = fireSimultaneously(2, i ->
                Api.purchase(ac[1], i == 0 ? 8_000 : 5_000, "PHARMACY").statusCode(201).extract().jsonPath());

        List<JsonPath> approved = withStatus(results, "APPROVED");
        List<JsonPath> declined = withStatus(results, "DECLINED");
        assertEquals(1, approved.size(), "exactly one of $80 / $50 must be approved");
        assertEquals(1, declined.size());
        assertEquals("INSUFFICIENT_FUNDS", declined.get(0).getString("transaction.declineReason"));

        long approvedAmount = approved.get(0).getLong("transaction.amountCents");
        long finalBalance = Api.balance(ac[0]);
        assertEquals(START_BALANCE - approvedAmount, finalBalance); // $20 or $50
        assertTrue(finalBalance == 2_000 || finalBalance == 5_000);
        assertLedgerMatchesBalance(ac[0], finalBalance, 3);
    }

    /** 20 simultaneous $80 purchases against $100: exactly one can ever fit. */
    @RepeatedTest(10)
    void twentyIdenticalPurchasesExactlyOneApproved() throws Exception {
        long[] ac = Api.fundedAccountWithCard(START_BALANCE);
        List<JsonPath> results = fireSimultaneously(THREADS, i ->
                Api.purchase(ac[1], 8_000, "PHARMACY").statusCode(201).extract().jsonPath());

        assertEquals(1, withStatus(results, "APPROVED").size(), "exactly one purchase must be approved");
        withStatus(results, "DECLINED").forEach(d ->
                assertEquals("INSUFFICIENT_FUNDS", d.getString("transaction.declineReason")));
        results.forEach(r -> assertTrue(r.getLong("balanceAfterCents") >= 0));
        assertEquals(2_000, Api.balance(ac[0]));
        assertLedgerMatchesBalance(ac[0], 2_000, THREADS + 1);
    }

    /**
     * 20 simultaneous purchases alternating $80 / $50 against $100.
     *
     * <p>Note: "exactly one approved" is NOT the right assertion here, because two $50 purchases
     * fit exactly into $100. The valid outcomes are {$80}, {$50} or {$50, $50}. What must always
     * hold is: no overspend, books balance, and no purchase was declined that could still fit
     * (since the balance only goes down, final balance must be below every declined amount).
     */
    @RepeatedTest(10)
    void twentyMixedPurchasesNeverOverspend() throws Exception {
        long[] ac = Api.fundedAccountWithCard(START_BALANCE);
        List<JsonPath> results = fireSimultaneously(THREADS, i ->
                Api.purchase(ac[1], (i % 2 == 0) ? 8_000 : 5_000, "PHARMACY").statusCode(201).extract().jsonPath());

        List<Long> approvedAmounts = withStatus(results, "APPROVED").stream()
                .map(r -> r.getLong("transaction.amountCents")).sorted().toList();
        assertTrue(approvedAmounts.equals(List.of(8_000L))
                        || approvedAmounts.equals(List.of(5_000L))
                        || approvedAmounts.equals(List.of(5_000L, 5_000L)),
                "unexpected approved set " + approvedAmounts);

        long spent = approvedAmounts.stream().mapToLong(Long::longValue).sum();
        long finalBalance = Api.balance(ac[0]);
        assertEquals(START_BALANCE - spent, finalBalance);
        assertTrue(finalBalance >= 0);
        results.forEach(r -> assertTrue(r.getLong("balanceAfterCents") >= 0));

        for (JsonPath d : withStatus(results, "DECLINED")) {
            assertEquals("INSUFFICIENT_FUNDS", d.getString("transaction.declineReason"));
            assertTrue(finalBalance < d.getLong("transaction.amountCents"),
                    "a declined purchase would still have fit: balance " + finalBalance);
        }
        assertLedgerMatchesBalance(ac[0], finalBalance, THREADS + 1);
    }

    private static List<JsonPath> withStatus(List<JsonPath> results, String status) {
        return results.stream().filter(r -> status.equals(r.getString("transaction.status"))).toList();
    }

    /** Concurrent deposits must not lose updates either (no read-modify-write anywhere). */
    @Test
    void concurrentDepositsAreAllApplied() throws Exception {
        long accountId = Api.createAccount();
        fireSimultaneously(THREADS, i -> Api.deposit(accountId, 500).statusCode(201).extract().jsonPath());
        assertEquals(THREADS * 500L, Api.balance(accountId));
        assertLedgerMatchesBalance(accountId, THREADS * 500L, THREADS);
    }

    /** Mixed: deposits racing purchases. Whatever interleaving happens, the books must balance. */
    @RepeatedTest(5)
    void depositsRacingPurchasesKeepBooksConsistent() throws Exception {
        long[] ac = Api.fundedAccountWithCard(START_BALANCE);
        List<JsonPath> results = fireSimultaneously(THREADS, i -> (i % 2 == 0)
                ? Api.deposit(ac[0], 3_000).statusCode(201).extract().jsonPath()
                : Api.purchase(ac[1], 4_000, "HOSPITAL").statusCode(201).extract().jsonPath());
        results.forEach(r -> assertTrue(r.getLong("balanceAfterCents") >= 0));
        long finalBalance = Api.balance(ac[0]);
        assertTrue(finalBalance >= 0);
        assertLedgerMatchesBalance(ac[0], finalBalance, THREADS + 1);
    }

    // ---------------------------------------------------------------------------------------------

    interface Call {
        JsonPath run(int i) throws Exception;
    }

    /**
     * Starts {@code n} threads, holds them all at a CountDownLatch "starting gun", then releases
     * them together so the requests genuinely overlap.
     */
    private static List<JsonPath> fireSimultaneously(int n, Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<JsonPath>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.run(idx);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "threads did not start");
            go.countDown();
            List<JsonPath> out = new ArrayList<>();
            for (Future<JsonPath> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertLedgerMatchesBalance(long accountId, long balance, int expectedRows) {
        JsonPath history = given().get("/api/accounts/{id}/transactions", accountId)
                .then().statusCode(200).extract().jsonPath();
        List<String> types = history.getList("type");
        List<String> statuses = history.getList("status");
        List<Number> amounts = history.getList("amountCents", Number.class);
        assertEquals(expectedRows, types.size(), "every attempt, approved or declined, is recorded");
        long net = 0;
        for (int i = 0; i < types.size(); i++) {
            if (!"APPROVED".equals(statuses.get(i))) {
                continue;
            }
            long amt = amounts.get(i).longValue();
            net += "DEPOSIT".equals(types.get(i)) ? amt : -amt;
        }
        assertEquals(balance, net, "sum of approved transactions must equal the cached balance");
    }
}
