package com.example.hsa;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AccountAndDepositTest {

    @Test
    void createsAccountWithZeroBalance() {
        String email = "Alice-" + UUID.randomUUID() + "@Example.com";
        given().contentType(ContentType.JSON)
                .body(Map.of("holderName", "Alice Smith", "email", email, "dateOfBirth", "1988-04-02"))
                .post("/api/accounts")
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("holderName", equalTo("Alice Smith"))
                .body("email", equalTo(email.toLowerCase()))
                .body("dateOfBirth", equalTo("1988-04-02"))
                .body("balanceCents", equalTo(0));
    }

    @Test
    void rejectsDuplicateEmailCaseInsensitively() {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        Map<String, Object> body = Map.of("holderName", "A", "email", email, "dateOfBirth", "1990-01-01");
        given().contentType(ContentType.JSON).body(body).post("/api/accounts").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body(Map.of("holderName", "B", "email", email.toUpperCase(), "dateOfBirth", "1990-01-01"))
                .post("/api/accounts")
                .then().statusCode(409).body("code", equalTo("EMAIL_TAKEN"));
    }

    @Test
    void rejectsInvalidAccountInput() {
        given().contentType(ContentType.JSON)
                .body(Map.of("holderName", "", "email", "not-an-email", "dateOfBirth", "1990-01-01"))
                .post("/api/accounts").then().statusCode(400);
        given().contentType(ContentType.JSON)
                .body(Map.of("holderName", "Future", "email", "f-" + UUID.randomUUID() + "@example.com",
                        "dateOfBirth", LocalDate.now().plusDays(1).toString()))
                .post("/api/accounts").then().statusCode(400);
    }

    @Test
    void unknownAccountIs404() {
        given().get("/api/accounts/{id}", 999_999_999L).then().statusCode(404).body("code", equalTo("NOT_FOUND"));
        Api.deposit(999_999_999L, 100).statusCode(404);
    }

    @Test
    void depositIncreasesBalanceAndIsRecorded() {
        long id = Api.createAccount();
        Api.deposit(id, 10_000).statusCode(201)
                .body("transaction.type", equalTo("DEPOSIT"))
                .body("transaction.status", equalTo("APPROVED"))
                .body("transaction.amountCents", equalTo(10_000))
                .body("balanceAfterCents", equalTo(10_000));
        Api.deposit(id, 2_550).statusCode(201).body("balanceAfterCents", equalTo(12_550));
        assertEquals(12_550, Api.balance(id));

        given().get("/api/accounts/{id}/transactions", id)
                .then().statusCode(200).body("size()", equalTo(2));
    }

    @Test
    void rejectsNonPositiveOrMissingDepositAmounts() {
        long id = Api.createAccount();
        Api.deposit(id, 0).statusCode(400);
        Api.deposit(id, -500).statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of())
                .post("/api/accounts/{id}/deposits", id).then().statusCode(400);
        assertEquals(0, Api.balance(id));
    }

    /** Amounts are integer cents: fractions and numeric strings are rejected, never truncated or coerced. */
    @ParameterizedTest
    @ValueSource(strings = {"12.5", "2500.99", "\"100\""})
    void nonIntegerDepositAmountsAre400(String rawAmount) {
        long id = Api.createAccount();
        given().contentType(ContentType.JSON).body("{\"amountCents\":" + rawAmount + "}")
                .post("/api/accounts/{id}/deposits", id).then().statusCode(400);
        assertEquals(0, Api.balance(id));
        // A real integer still works.
        given().contentType(ContentType.JSON).body("{\"amountCents\":1250}")
                .post("/api/accounts/{id}/deposits", id).then().statusCode(201);
        assertEquals(1_250, Api.balance(id));
    }
}
