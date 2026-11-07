package com.example.hsa.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

/**
 * Money amounts are integer cents, so JSON must send them as integers. By default Jackson would
 * silently turn 12.5 into 12 and the string "100" into 100; here both are rejected and the request
 * fails with HTTP 400 before reaching any service.
 */
@Singleton
public class StrictJsonConfig implements ObjectMapperCustomizer {

    @Override
    public void customize(ObjectMapper mapper) {
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
    }
}
