package com.example.commerce.mapping;

import com.example.commerce.integration.ProtocolExecutor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class SupplierCommerceGateway {
    public record GatewayResult(String supplierId, int mappingVersion, String operation, JsonNode response) {}
    private final SupplierMappingRegistry registry;
    private final MappingEngine engine;
    private final ProtocolExecutor executor;

    public SupplierCommerceGateway(SupplierMappingRegistry registry, MappingEngine engine, ProtocolExecutor executor) {
        this.registry = registry; this.engine = engine; this.executor = executor;
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
        JsonNode incoming = executor.execute(route.route(), outgoing);
        if (incoming == null) throw new MappingException("Resposta do parceiro nula");
        return new GatewayResult(version.supplierId(), version.version(), operation, engine.map(incoming, route.response()));
    }
}
