package com.example.hsa.api;

import com.example.hsa.api.Dtos.ApiError;
import com.example.hsa.service.ApiException;
import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.core.Response;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.Map;

/** Turns business errors and unique-constraint violations into JSON {code, message} responses. */
public class ErrorMappers {

    private static final Logger LOG = Logger.getLogger(ErrorMappers.class);

    /** Unique constraints from V1__init.sql that represent a client conflict (409), not a server bug. */
    private static final Map<String, ApiError> CONFLICTS = Map.of(
            "uq_account_email",
            new ApiError("EMAIL_TAKEN", "An account with that email already exists"),
            "uq_card_one_active_per_account",
            new ApiError("CARD_ALREADY_ACTIVE", "Account already has an active card; freeze it first"));

    @ServerExceptionMapper
    public Response mapApi(ApiException e) {
        return Response.status(e.status).entity(new ApiError(e.code, e.getMessage())).build();
    }

    @ServerExceptionMapper
    public Response mapPersistence(PersistenceException e) {
        ApiError conflict = CONFLICTS.get(violatedConstraint(e));
        if (conflict != null) {
            return Response.status(409).entity(conflict).build();
        }
        LOG.error("Unhandled persistence error", e);
        return Response.serverError().entity(new ApiError("INTERNAL", "Unexpected database error")).build();
    }

    /** Name of the violated constraint, without MySQL's "table." prefix, or null if there is none. */
    private static String violatedConstraint(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                String name = cve.getConstraintName();
                return name.substring(name.lastIndexOf('.') + 1).toLowerCase();
            }
        }
        return null;
    }
}
