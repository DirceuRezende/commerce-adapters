package com.example.commerce.mapping;

import com.example.commerce.CommerceException;
import com.example.commerce.integration.ProtocolAdapter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SupplierCommerceGatewayTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final MappingEngine engine = new MappingEngine(json);
    private final SupplierMappingRegistry registry = new SupplierMappingRegistry(json, engine);

    @Test void keepsRequestAndResponseOnSameVersionWhenActiveVersionChanges() {
        registry.publish(definition(1));
        registry.publish(definition(2));
        registry.activate("supplier", 1);
        var adapter = new ProtocolAdapter() {
            @Override public String transport() { return "REST"; }
            @Override public JsonNode execute(SupplierMappingRegistry.Route route, JsonNode payload) {
                registry.activate("supplier", 2);
                return payload;
            }
        };
        var gateway = new SupplierCommerceGateway(registry, engine, List.of(adapter));

        var first = gateway.execute("supplier", "createCart", json.createObjectNode());
        assertEquals(1, first.mappingVersion());
        assertEquals(1, first.response().path("version").intValue());
        assertEquals(1, first.response().path("contract").intValue());
        assertEquals(2, registry.active("supplier").version());

        var second = gateway.execute("supplier", "createCart", json.createObjectNode());
        assertEquals(2, second.mappingVersion());
        assertEquals(2, second.response().path("contract").intValue());
        assertEquals(2, second.response().path("version").intValue());
        assertEquals(1, gateway.executeVersion("supplier", 1, "createCart", json.createObjectNode())
            .response().path("version").intValue());
    }

    @Test void rejectsInvalidPublicationWithoutChangingActiveVersion() {
        registry.publish(definition(1));
        registry.activate("supplier", 1);
        var invalid = definition(2).replace("\"literal\":2", "\"path\":\"invalid\"");
        assertThrows(CommerceException.class, () -> registry.publish(invalid));
        assertEquals(1, registry.active("supplier").version());
        assertThrows(CommerceException.class, () -> registry.version("supplier", 2));
    }

    @Test void reportsUnsupportedTransportWithStructuredError() {
        registry.publish(definition(1));
        registry.activate("supplier", 1);
        var gateway = new SupplierCommerceGateway(registry, engine, List.of());
        var error = assertThrows(CommerceException.class,
            () -> gateway.execute("supplier", "createCart", json.createObjectNode()));
        assertEquals("TRANSPORT_UNSUPPORTED", error.code());
    }

    private String definition(int version) {
        return """
            {"supplierId":"supplier","version":%d,"operations":{"createCart":{
              "route":{"transport":"REST","connection":"local","path":"/carts","action":"POST"},
              "requestMapping":{"version":{"literal":%d}},
              "responseMapping":{"version":{"path":"$.version"},"contract":{"literal":%d}}
            }}}
            """.formatted(version, version, version);
    }
}
