package com.example.commerce.integration;

import com.example.commerce.mapping.SupplierMappingRegistry.Route;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class GraphqlProtocolAdapter implements ProtocolAdapter {
    private final HttpJsonExchange http;
    public GraphqlProtocolAdapter(HttpJsonExchange http) { this.http = http; }
    @Override public String transport() { return "GRAPHQL"; }
    @Override public JsonNode execute(Route route, JsonNode payload) {
        if (!payload.isObject() || !payload.path("query").isTextual())
            throw new IntegrationException("INVALID_GRAPHQL_REQUEST", null, "GraphQL exige query textual");
        JsonNode result = http.execute(route, "POST", payload, false);
        JsonNode errors = result.path("errors");
        // Strict policy: partial success with errors is NOT silently accepted for commerce operations.
        if (errors.isArray() && !errors.isEmpty())
            throw new IntegrationException("GRAPHQL_ERRORS", 200, "GraphQL retornou errors");
        JsonNode data = result.path("data");
        if (data.isMissingNode() || data.isNull() || !data.isObject())
            throw new IntegrationException("GRAPHQL_NO_DATA", 200, "GraphQL sem objeto data");
        return data; // subsequent JsonPath rules start at $.field, not $.data.field
    }
}
