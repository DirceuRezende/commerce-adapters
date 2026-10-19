package com.example.commerce.integration;

import com.example.commerce.mapping.SupplierMappingRegistry.Route;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ProtocolExecutor {
    private final java.util.Map<String, ProtocolAdapter> adapters;
    public ProtocolExecutor(List<ProtocolAdapter> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(ProtocolAdapter::transport, Function.identity()));
    }
    public JsonNode execute(Route route, JsonNode payload) {
        ProtocolAdapter adapter = adapters.get(route.transport());
        if (adapter == null) throw new IntegrationException("TRANSPORT_UNSUPPORTED", null, route.transport());
        return adapter.execute(route, payload);
    }
}
