package com.example.hsa;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

@QuarkusTest
class CardTest {

    @Test
    void issuesMaskedActiveCard() {
        long accountId = Api.createAccount();
        given().post("/api/accounts/{id}/cards", accountId)
                .then().statusCode(201)
                .body("accountId", equalTo((int) accountId))
                .body("status", equalTo("ACTIVE"))
                .body("maskedNumber", matchesPattern("\\*{4} \\*{4} \\*{4} \\d{4}"))
                .body("expiry", matchesPattern("\\d{2}/\\d{2}"));
    }

    @Test
    void onlyOneActiveCardPerAccount() {
        long accountId = Api.createAccount();
        long first = Api.issueCard(accountId);
        given().post("/api/accounts/{id}/cards", accountId)
                .then().statusCode(409).body("code", equalTo("CARD_ALREADY_ACTIVE"));

        // Freezing the first card allows a replacement...
        given().post("/api/cards/{id}/freeze", first).then().statusCode(200).body("status", equalTo("FROZEN"));
        long second = Api.issueCard(accountId);

        // ...and then the old one can't be reactivated while the new one is active.
        given().post("/api/cards/{id}/unfreeze", first)
                .then().statusCode(409).body("code", equalTo("CARD_ALREADY_ACTIVE"));

        given().get("/api/accounts/{id}/cards", accountId)
                .then().statusCode(200).body("size()", equalTo(2))
                .body("find { it.id == %s }.status".formatted(second), equalTo("ACTIVE"));
    }

    @Test
    void cardForUnknownAccountIs404() {
        given().post("/api/accounts/{id}/cards", 999_999_999L).then().statusCode(404);
        given().post("/api/cards/{id}/freeze", 999_999_999L).then().statusCode(404);
    }
}
