package com.example.hsa;

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/** Small helpers so tests read as scenarios. Every account gets a unique email, so tests never collide. */
final class Api {

    private Api() {
    }

    static long createAccount() {
        return given().contentType(ContentType.JSON)
                .body(Map.of(
                        "holderName", "Test Holder",
                        "email", "t-" + UUID.randomUUID() + "@example.com",
                        "dateOfBirth", "1990-01-15"))
                .post("/api/accounts")
                .then().statusCode(201)
                .extract().jsonPath().getLong("id");
    }

    static ValidatableResponse deposit(long accountId, long cents) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("amountCents", cents))
                .post("/api/accounts/{id}/deposits", accountId)
                .then();
    }

    static long issueCard(long accountId) {
        return given().contentType(ContentType.JSON)
                .post("/api/accounts/{id}/cards", accountId)
                .then().statusCode(201)
                .extract().jsonPath().getLong("id");
    }

    static ValidatableResponse purchase(long cardId, long cents, String category) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("amountCents", cents, "merchantName", "Test Merchant", "merchantCategory", category))
                .post("/api/cards/{id}/purchases", cardId)
                .then();
    }

    static long balance(long accountId) {
        return given().get("/api/accounts/{id}", accountId)
                .then().statusCode(200)
                .extract().jsonPath().getLong("balanceCents");
    }

    /** Account with a funded active card. Returns {accountId, cardId}. */
    static long[] fundedAccountWithCard(long cents) {
        long accountId = createAccount();
        deposit(accountId, cents).statusCode(201);
        long cardId = issueCard(accountId);
        return new long[]{accountId, cardId};
    }
}
