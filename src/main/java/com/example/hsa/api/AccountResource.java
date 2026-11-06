package com.example.hsa.api;

import com.example.hsa.api.Dtos.AccountResponse;
import com.example.hsa.api.Dtos.CardResponse;
import com.example.hsa.api.Dtos.CreateAccountRequest;
import com.example.hsa.api.Dtos.DepositRequest;
import com.example.hsa.api.Dtos.TransactionResponse;
import com.example.hsa.api.Dtos.TxResultResponse;
import com.example.hsa.service.AccountService;
import com.example.hsa.service.CardService;
import com.example.hsa.service.Constraints;
import com.example.hsa.service.TxResult;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccountResource {

    @Inject
    AccountService accounts;

    @Inject
    CardService cards;

    @POST
    public Response create(@Valid @NotNull CreateAccountRequest req) {
        var a = accounts.create(req.holderName(), req.email(), req.dateOfBirth());
        return Response.status(Response.Status.CREATED).entity(AccountResponse.of(a)).build();
    }

    @GET
    public List<AccountResponse> list() {
        return accounts.listAll().stream().map(AccountResponse::of).toList();
    }

    @GET
    @Path("/{id}")
    public AccountResponse get(@PathParam("id") long id) {
        return AccountResponse.of(accounts.get(id));
    }

    @POST
    @Path("/{id}/deposits")
    public Response deposit(@PathParam("id") long id,
                            @HeaderParam("Idempotency-Key") @Size(max = 100) String idempotencyKey,
                            @Valid @NotNull DepositRequest req) {
        TxResult r;
        try {
            r = accounts.deposit(id, req.amountCents(), idempotencyKey);
        } catch (RuntimeException e) {
            // Two requests with the same new key raced; the loser's insert hit the unique index and
            // its whole transaction (including the credit) rolled back. Retrying now replays the winner.
            if (idempotencyKey == null || !Constraints.isViolation(e, Constraints.UQ_TX_IDEMPOTENCY_KEY)) {
                throw e;
            }
            r = accounts.deposit(id, req.amountCents(), idempotencyKey);
        }
        return Response.status(r.replayed() ? 200 : 201).entity(TxResultResponse.of(r)).build();
    }

    @POST
    @Consumes(MediaType.WILDCARD) // no request body
    @Path("/{id}/cards")
    public Response issueCard(@PathParam("id") long id) {
        return Response.status(Response.Status.CREATED).entity(CardResponse.of(cards.issue(id))).build();
    }

    @GET
    @Path("/{id}/cards")
    public List<CardResponse> listCards(@PathParam("id") long id) {
        return cards.forAccount(id).stream().map(CardResponse::of).toList();
    }

    @GET
    @Path("/{id}/transactions")
    public List<TransactionResponse> transactions(@PathParam("id") long id) {
        return accounts.transactions(id).stream().map(TransactionResponse::of).toList();
    }
}
