package com.example.commerce.mapping;

import com.example.commerce.CommerceException;
import com.example.commerce.integration.HttpAdapter;
import com.example.commerce.integration.McpAdapter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static com.example.commerce.mapping.SupplierCommerceGateway.Transport.UCP_MCP;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SupplierCommerceGatewayTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final MappingEngine engine = new MappingEngine(json);
    private final HttpAdapter http = mock(HttpAdapter.class);
    private final McpAdapter mcp = mock(McpAdapter.class);
    private final SupplierCommerceGateway gateway = new SupplierCommerceGateway(json, engine, http, mcp);

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

    @Test void shopifyPlatformSupplierInheritsStandardOperationsBoundToItsConnection() {
        var supplier = gateway.publish("""
            {"supplierId":"loja-x","version":1,"platform":"shopify","connection":"loja-x"}
            """);
        gateway.activate("loja-x", 1);

        assertEquals(Set.of("searchCatalog", "createCart"), supplier.operations().keySet());
        var route = supplier.operation("searchCatalog").route();
        assertEquals(UCP_MCP, route.transport());
        assertEquals("loja-x", route.connection());
        assertEquals("search_catalog", route.action());
        assertEquals("loja-x", supplier.operation("createCart").route().connection());

        when(mcp.call(any(), any())).thenReturn(json.readTree("""
            {"products":[{"id":"gid://shopify/Product/1","title":"Ração","price_range":{"min":{"amount":46124,"currency":"BRL"}}}]}
            """));
        var result = gateway.execute("loja-x", "searchCatalog", json.readTree("""
            {"query":"ração","limit":2}
            """));

        verify(mcp).call(eq(route), argThat((JsonNode args) -> args.at("/catalog/query").asText().equals("ração")
            && args.at("/catalog/pagination/limit").intValue() == 2
            && args.at("/meta/ucp-agent/profile").isTextual()));
        assertEquals("Ração", result.response().at("/products/0/name").asText());
        assertEquals(46124, result.response().at("/products/0/priceMinor").intValue());
    }

    @Test void platformSupplierCanOverrideOneOperationAndAddNewOnes() {
        var supplier = gateway.publish("""
            {"supplierId":"loja-y","version":1,"platform":"shopify","connection":"loja-y","operations":{
              "searchCatalog":{"responseMapping":{"products":{"path":"$.products[*]","each":{"id":{"path":"$.handle"}}}}},
              "getProduct":{
                "route":{"transport":"UCP_MCP","connection":"loja-y","path":"/api/ucp/mcp","action":"get_product"},
                "requestMapping":{"catalog":{"path":"$"}},
                "responseMapping":{"id":{"path":"$.product.id"}}}
            }}
            """);

        assertEquals(Set.of("searchCatalog", "createCart", "getProduct"), supplier.operations().keySet());
        var search = supplier.operation("searchCatalog");
        assertEquals("search_catalog", search.route().action(), "route herdada do template");
        assertEquals("loja-y", search.route().connection());
        assertTrue(search.request().containsKey("meta"), "requestMapping herdado do template");
        assertFalse(search.response().get("products").each().containsKey("name"), "responseMapping sobrescrito");
    }

    @Test void rejectsUnknownPlatformAndMissingConnection() {
        assertThrows(CommerceException.class, () -> gateway.publish("""
            {"supplierId":"loja-z","version":1,"platform":"vtex","connection":"loja-z"}
            """));
        assertThrows(CommerceException.class, () -> gateway.publish("""
            {"supplierId":"loja-z","version":1,"platform":"shopify"}
            """));
        assertThrows(CommerceException.class, () -> gateway.version("loja-z", 1));
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
