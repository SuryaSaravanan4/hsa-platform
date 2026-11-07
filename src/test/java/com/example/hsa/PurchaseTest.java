package com.example.hsa;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class PurchaseTest {

    @Inject
    TestClock clock;

    @ParameterizedTest
    @ValueSource(strings = {"PHARMACY", "HOSPITAL"})
    void qualifiedPurchaseIsApprovedAndDebited(String category) {
        long[] ac = Api.fundedAccountWithCard(10_000);
        Api.purchase(ac[1], 2_599, category).statusCode(201)
                .body("transaction.type", equalTo("PURCHASE"))
                .body("transaction.status", equalTo("APPROVED"))
                .body("transaction.declineReason", nullValue())
                .body("transaction.merchantCategory", equalTo(category))
                .body("balanceAfterCents", equalTo(7_401));
        assertEquals(7_401, Api.balance(ac[0]));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESTAURANT", "ELECTRONICS"})
    void nonQualifiedPurchaseIsDeclinedAndBalanceUntouched(String category) {
        long[] ac = Api.fundedAccountWithCard(10_000);
        Api.purchase(ac[1], 1_000, category).statusCode(201)
                .body("transaction.status", equalTo("DECLINED"))
                .body("transaction.declineReason", equalTo("NOT_QUALIFIED"))
                .body("balanceAfterCents", equalTo(10_000));
        assertEquals(10_000, Api.balance(ac[0]));
    }

    @Test
    void insufficientFundsIsDeclined() {
        long[] ac = Api.fundedAccountWithCard(5_000);
        Api.purchase(ac[1], 5_001, "PHARMACY").statusCode(201)
                .body("transaction.status", equalTo("DECLINED"))
                .body("transaction.declineReason", equalTo("INSUFFICIENT_FUNDS"));
        assertEquals(5_000, Api.balance(ac[0]));

        // Spending the exact balance is allowed and leaves zero.
        Api.purchase(ac[1], 5_000, "PHARMACY").statusCode(201).body("transaction.status", equalTo("APPROVED"));
        assertEquals(0, Api.balance(ac[0]));
    }

    @Test
    void frozenCardIsDeclinedAsCardInactive() {
        long[] ac = Api.fundedAccountWithCard(10_000);
        given().post("/api/cards/{id}/freeze", ac[1]).then().statusCode(200);
        Api.purchase(ac[1], 1_000, "PHARMACY").statusCode(201)
                .body("transaction.status", equalTo("DECLINED"))
                .body("transaction.declineReason", equalTo("CARD_INACTIVE"));
        assertEquals(10_000, Api.balance(ac[0]));

        given().post("/api/cards/{id}/unfreeze", ac[1]).then().statusCode(200);
        Api.purchase(ac[1], 1_000, "PHARMACY").statusCode(201).body("transaction.status", equalTo("APPROVED"));
    }

    /** A card works through the last day of its expiry month and is CARD_INACTIVE from the next day. */
    @Test
    void expiredCardIsDeclinedAsCardInactive() {
        long[] ac = Api.fundedAccountWithCard(10_000);
        String expiry = given().get("/api/accounts/{id}/cards", ac[0])
                .then().statusCode(200).extract().jsonPath().getString("[0].expiry"); // "MM/yy"
        LocalDate lastValidDay = YearMonth.parse(expiry, DateTimeFormatter.ofPattern("MM/yy")).atEndOfMonth();
        try {
            clock.setTo(lastValidDay.atTime(23, 59).toInstant(ZoneOffset.UTC));
            Api.purchase(ac[1], 1_000, "PHARMACY").statusCode(201)
                    .body("transaction.status", equalTo("APPROVED"));

            clock.setTo(lastValidDay.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
            Api.purchase(ac[1], 1_000, "PHARMACY").statusCode(201)
                    .body("transaction.status", equalTo("DECLINED"))
                    .body("transaction.declineReason", equalTo("CARD_INACTIVE"));
        } finally {
            clock.reset();
        }
        assertEquals(9_000, Api.balance(ac[0]));
    }

    @Test
    void cardCheckComesBeforeCategoryCheck() {
        long[] ac = Api.fundedAccountWithCard(10_000);
        given().post("/api/cards/{id}/freeze", ac[1]).then().statusCode(200);
        Api.purchase(ac[1], 1_000, "RESTAURANT").statusCode(201)
                .body("transaction.declineReason", equalTo("CARD_INACTIVE"));
    }

    @Test
    void declinesAreRecordedInHistory() {
        long[] ac = Api.fundedAccountWithCard(10_000);
        Api.purchase(ac[1], 1_000, "PHARMACY").statusCode(201);
        Api.purchase(ac[1], 1_000, "RESTAURANT").statusCode(201);
        Api.purchase(ac[1], 99_000, "HOSPITAL").statusCode(201);

        given().get("/api/accounts/{id}/transactions", ac[0]).then().statusCode(200)
                .body("size()", equalTo(4)) // deposit + 3 purchases
                .body("findAll { it.status == 'DECLINED' }.declineReason",
                        containsInAnyOrder("NOT_QUALIFIED", "INSUFFICIENT_FUNDS"));
        assertEquals(9_000, Api.balance(ac[0]));
    }

    @Test
    void invalidPurchaseRequestsAre400() {
        long[] ac = Api.fundedAccountWithCard(10_000);
        Api.purchase(ac[1], 0, "PHARMACY").statusCode(400);
        Api.purchase(ac[1], -1, "PHARMACY").statusCode(400);
        Api.purchase(ac[1], 100, "NOT_A_CATEGORY").statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("amountCents", 100, "merchantCategory", "PHARMACY"))
                .post("/api/cards/{id}/purchases", ac[1]).then().statusCode(400);
        assertEquals(10_000, Api.balance(ac[0]));
    }

    @Test
    void unknownCardIs404() {
        Api.purchase(999_999_999L, 100, "PHARMACY").statusCode(404);
    }

    @Test
    void categoriesEndpointReflectsPolicy() {
        given().get("/api/categories").then().statusCode(200)
                .body("find { it.category == 'PHARMACY' }.qualified", equalTo(true))
                .body("find { it.category == 'HOSPITAL' }.qualified", equalTo(true))
                .body("find { it.category == 'RESTAURANT' }.qualified", equalTo(false))
                .body("find { it.category == 'ELECTRONICS' }.qualified", equalTo(false));
    }
}
