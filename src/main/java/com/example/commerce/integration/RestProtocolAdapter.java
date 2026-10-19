package com.example.commerce.integration;

import com.example.commerce.mapping.SupplierMappingRegistry.Route;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class RestProtocolAdapter implements ProtocolAdapter {
    private final HttpJsonExchange http;
    public RestProtocolAdapter(HttpJsonExchange http) { this.http = http; }
    @Override public String transport() { return "REST"; }
    @Override public JsonNode execute(Route route, JsonNode payload) {
        return http.execute(route, route.action(), payload, "GET".equalsIgnoreCase(route.action()));
    }
}
