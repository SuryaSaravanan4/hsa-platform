package com.example.hsa.api;

import com.example.hsa.api.Dtos.CategoryResponse;
import com.example.hsa.domain.MerchantCategory;
import com.example.hsa.domain.QualifiedExpensePolicy;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Arrays;
import java.util.List;

@Path("/api/categories")
@Produces(MediaType.APPLICATION_JSON)
public class CategoryResource {

    @Inject
    QualifiedExpensePolicy policy;

    @GET
    public List<CategoryResponse> list() {
        return Arrays.stream(MerchantCategory.values())
                .map(c -> new CategoryResponse(c, policy.isQualified(c)))
                .toList();
    }
}
