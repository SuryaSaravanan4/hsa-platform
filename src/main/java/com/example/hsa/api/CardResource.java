package com.example.hsa.api;

import com.example.hsa.api.Dtos.CardResponse;
import com.example.hsa.api.Dtos.PurchaseRequest;
import com.example.hsa.api.Dtos.TxResultResponse;
import com.example.hsa.domain.CardStatus;
import com.example.hsa.service.CardService;
import com.example.hsa.service.Constraints;
import com.example.hsa.service.PurchaseService;
import com.example.hsa.service.TxResult;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.*;
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
    @Path("/{id}/freeze")
    public CardResponse freeze(@PathParam("id") long id) {
        return CardResponse.of(cards.setStatus(id, CardStatus.FROZEN));
    }

    @POST
    @Path("/{id}/unfreeze")
    public CardResponse unfreeze(@PathParam("id") long id) {
        return CardResponse.of(cards.setStatus(id, CardStatus.ACTIVE));
    }

    /**
     * Simulates the card network asking us to authorize a purchase.
     * A decline is a normal business outcome, not an HTTP error: it returns 201 with
     * status=DECLINED and a declineReason. A replayed idempotency key returns 200.
     */
    @POST
    @Path("/{id}/purchases")
    public Response purchase(@PathParam("id") long id,
                             @HeaderParam("Idempotency-Key") @Size(max = 100) String idempotencyKey,
                             @Valid @NotNull PurchaseRequest req) {
        TxResult r;
        try {
            r = purchases.purchase(id, req.amountCents(), req.merchantName().trim(), req.merchantCategory(),
                    idempotencyKey);
        } catch (RuntimeException e) {
            // Same-key race: the loser's transaction (debit included) rolled back; retry replays the winner.
            if (idempotencyKey == null || !Constraints.isViolation(e, Constraints.UQ_TX_IDEMPOTENCY_KEY)) {
                throw e;
            }
            r = purchases.purchase(id, req.amountCents(), req.merchantName().trim(), req.merchantCategory(),
                    idempotencyKey);
        }
        return Response.status(r.replayed() ? 200 : 201).entity(TxResultResponse.of(r)).build();
    }
}
