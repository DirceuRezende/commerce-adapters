package com.example.commerce.web;

import com.example.commerce.CommerceException;
import com.example.commerce.mapping.SupplierCommerceGateway;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Canonical catalog search; the supplier's mapping decides the transport (REST, GraphQL or UCP MCP). */
@RestController
@RequestMapping("/suppliers/{supplierId}/catalog")
public class CatalogController {
    private final SupplierCommerceGateway gateway;
    private final JsonMapper mapper;

    public CatalogController(SupplierCommerceGateway gateway, JsonMapper mapper) {
        this.gateway = gateway; this.mapper = mapper;
    }

    @GetMapping("/search")
    public JsonNode search(@PathVariable String supplierId, @RequestParam String query,
                           @RequestParam(required = false) Integer limit) {
        var request = mapper.createObjectNode().put("query", query);
        if (limit != null) request.put("limit", limit);
        return gateway.execute(supplierId, "searchCatalog", request).response();
    }

    @ExceptionHandler(CommerceException.class)
    ProblemDetail handle(CommerceException ex) {
        // Mapping errors come from the request or supplier setup; everything else is a partner/integration failure.
        HttpStatus status = "MAPPING_ERROR".equals(ex.code()) ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(ex.code());
        return problem;
    }
}
