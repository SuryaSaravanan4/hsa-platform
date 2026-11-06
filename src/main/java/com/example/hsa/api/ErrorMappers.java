package com.example.hsa.api;

import com.example.hsa.api.Dtos.ApiError;
import com.example.hsa.service.ApiException;
import com.example.hsa.service.Constraints;
import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Turns business and constraint errors into JSON {code, message} responses. */
public class ErrorMappers {

    private static final Logger LOG = Logger.getLogger(ErrorMappers.class);

    @ServerExceptionMapper
    public Response mapApi(ApiException e) {
        return Response.status(e.status).entity(new ApiError(e.code, e.getMessage())).build();
    }

    /**
     * Unique-constraint races that slip past the service-level pre-checks (two requests at once)
     * still end up as a clean 409 rather than a 500.
     */
    @ServerExceptionMapper
    public Response mapPersistence(PersistenceException e) {
        if (Constraints.isViolation(e, Constraints.UQ_ACCOUNT_EMAIL)) {
            return conflict("EMAIL_TAKEN", "An account with that email already exists");
        }
        if (Constraints.isViolation(e, Constraints.UQ_ONE_ACTIVE_CARD)) {
            return conflict("CARD_ALREADY_ACTIVE", "Account already has an active card");
        }
        LOG.error("Unhandled persistence error", e);
        return Response.serverError().entity(new ApiError("INTERNAL", "Unexpected database error")).build();
    }

    private static Response conflict(String code, String message) {
        return Response.status(409).entity(new ApiError(code, message)).build();
    }
}
