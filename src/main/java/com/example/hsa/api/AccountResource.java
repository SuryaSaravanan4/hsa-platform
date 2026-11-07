package com.example.hsa.api;

import com.example.hsa.api.Dtos.AccountResponse;
import com.example.hsa.api.Dtos.CardResponse;
import com.example.hsa.api.Dtos.CreateAccountRequest;
import com.example.hsa.api.Dtos.DepositRequest;
import com.example.hsa.api.Dtos.TransactionResponse;
import com.example.hsa.api.Dtos.TxResultResponse;
import com.example.hsa.service.AccountService;
import com.example.hsa.service.CardService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
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
    public Response deposit(@PathParam("id") long id, @Valid @NotNull DepositRequest req) {
        var r = accounts.deposit(id, req.amountCents());
        return Response.status(Response.Status.CREATED).entity(TxResultResponse.of(r)).build();
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
