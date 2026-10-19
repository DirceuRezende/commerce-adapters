package com.example.commerce.mapping;

import com.example.commerce.CommerceException;
import com.example.commerce.integration.ProtocolAdapter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class SupplierCommerceGateway {
    public record GatewayResult(String supplierId, int mappingVersion, String operation, JsonNode response) {}
    private final SupplierMappingRegistry registry;
    private final MappingEngine engine;
    private final Map<String, ProtocolAdapter> adapters;

    public SupplierCommerceGateway(SupplierMappingRegistry registry, MappingEngine engine, List<ProtocolAdapter> adapters) {
        this.registry = registry;
        this.engine = engine;
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(ProtocolAdapter::transport, Function.identity()));
    }
    public GatewayResult execute(String supplier, String operation, JsonNode canonicalRequest) {
        return executePinned(registry.active(supplier), operation, canonicalRequest);
    }
    public GatewayResult executeVersion(String supplier, int version, String operation, JsonNode canonicalRequest) {
        return executePinned(registry.version(supplier, version), operation, canonicalRequest);
    }
    private GatewayResult executePinned(SupplierMappingRegistry.SupplierVersion version, String operation, JsonNode input) {
        // Pin the SAME immutable version for request mapping and response mapping.
        var route = version.operation(operation);
        JsonNode outgoing = engine.map(input, route.request());
        ProtocolAdapter adapter = adapters.get(route.route().transport());
        if (adapter == null)
            throw new CommerceException("TRANSPORT_UNSUPPORTED", null, route.route().transport());
        JsonNode incoming = adapter.execute(route.route(), outgoing);
        if (incoming == null) throw new CommerceException("Resposta do parceiro nula");
        return new GatewayResult(version.supplierId(), version.version(), operation, engine.map(incoming, route.response()));
    }
}
