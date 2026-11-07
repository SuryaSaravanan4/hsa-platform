package com.example.hsa.api;

import com.example.hsa.api.Dtos.CardResponse;
import com.example.hsa.api.Dtos.PurchaseRequest;
import com.example.hsa.api.Dtos.TxResultResponse;
import com.example.hsa.domain.CardStatus;
import com.example.hsa.service.CardService;
import com.example.hsa.service.PurchaseService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/cards")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CardResource {

    @Inject
    CardService cards;

    @Inject
    PurchaseService purchases;

    @POST
    @Consumes(MediaType.WILDCARD) // no request body
    @Path("/{id}/freeze")
    public CardResponse freeze(@PathParam("id") long id) {
        return CardResponse.of(cards.setStatus(id, CardStatus.FROZEN));
    }

    @POST
    @Consumes(MediaType.WILDCARD) // no request body
    @Path("/{id}/unfreeze")
    public CardResponse unfreeze(@PathParam("id") long id) {
        return CardResponse.of(cards.setStatus(id, CardStatus.ACTIVE));
    }

    /**
     * Simulates the card network asking us to authorize a purchase.
     * A decline is a normal business outcome, not an HTTP error: it returns 201 with
     * status=DECLINED and a declineReason.
     */
    @POST
    @Path("/{id}/purchases")
    public Response purchase(@PathParam("id") long id, @Valid @NotNull PurchaseRequest req) {
        var r = purchases.purchase(id, req.amountCents(), req.merchantName().trim(), req.merchantCategory());
        return Response.status(Response.Status.CREATED).entity(TxResultResponse.of(r)).build();
    }
}
