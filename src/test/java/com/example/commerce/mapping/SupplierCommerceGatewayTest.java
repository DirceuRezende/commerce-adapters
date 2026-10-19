package com.example.commerce.mapping;

import com.example.commerce.CommerceException;
import com.example.commerce.integration.HttpAdapter;
import com.example.commerce.integration.McpAdapter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SupplierCommerceGatewayTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final MappingEngine engine = new MappingEngine(json);
    private final HttpAdapter http = mock(HttpAdapter.class);
    private final SupplierCommerceGateway gateway = new SupplierCommerceGateway(json, engine, http, mock(McpAdapter.class));

    @Test void keepsRequestAndResponseOnSameVersionWhenActiveVersionChanges() {
        gateway.publish(definition(1));
        gateway.publish(definition(2));
        gateway.activate("supplier", 1);
        when(http.rest(any(), any())).thenAnswer(call -> {
            gateway.activate("supplier", 2);
            return call.getArgument(1, JsonNode.class);
        });

        var first = gateway.execute("supplier", "createCart", json.createObjectNode());
        assertEquals(1, first.mappingVersion());
        assertEquals(1, first.response().path("version").intValue());
        assertEquals(1, first.response().path("contract").intValue());
        assertEquals(2, gateway.active("supplier").version());

        var second = gateway.execute("supplier", "createCart", json.createObjectNode());
        assertEquals(2, second.mappingVersion());
        assertEquals(2, second.response().path("contract").intValue());
        assertEquals(2, second.response().path("version").intValue());
        assertEquals(1, gateway.executeVersion("supplier", 1, "createCart", json.createObjectNode())
            .response().path("version").intValue());
    }

    @Test void rejectsInvalidPublicationWithoutChangingActiveVersion() {
        gateway.publish(definition(1));
        gateway.activate("supplier", 1);
        var invalid = definition(2).replace("\"literal\":2", "\"path\":\"invalid\"");
        assertThrows(CommerceException.class, () -> gateway.publish(invalid));
        assertEquals(1, gateway.active("supplier").version());
        assertThrows(CommerceException.class, () -> gateway.version("supplier", 2));
    }

    @Test void rejectsUnknownTransportOnPublish() {
        var invalid = definition(1).replace("\"transport\":\"REST\"", "\"transport\":\"SOAP\"");
        assertThrows(CommerceException.class, () -> gateway.publish(invalid));
        assertThrows(CommerceException.class, () -> gateway.version("supplier", 1));
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
