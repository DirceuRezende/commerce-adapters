package com.example.commerce.integration;

import com.example.commerce.mapping.SupplierMappingRegistry.Route;
import tools.jackson.databind.JsonNode;

public interface ProtocolAdapter {
    String transport();
    JsonNode execute(Route route, JsonNode payload);
}
