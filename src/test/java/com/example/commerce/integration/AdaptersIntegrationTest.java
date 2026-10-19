package com.example.commerce.integration;

import com.example.commerce.CommerceException;

import com.example.commerce.mapping.*;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static com.example.commerce.mapping.SupplierCommerceGateway.Transport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdaptersIntegrationTest {
    private HttpServer server;
    private int port;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<String> seenAuthorization = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private final AtomicReference<String> seenQuery = new AtomicReference<>();
    private PartnerConnections configs;
    private HttpAdapter http;
    private SupplierCommerceGateway gateway;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/carts", ex -> {
            seenQuery.set(ex.getRequestURI().getRawQuery());
            seenAuthorization.set(ex.getRequestHeaders().getFirst("Authorization"));
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"cart_id\":\"C-99\",\"status\":\"created\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(201, body.length);
            try (var out = ex.getResponseBody()) { out.write(body); }
        });
        server.createContext("/graphql", ex -> {
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"data\":{\"createCart\":{\"id\":\"G-77\",\"status\":\"created\"}}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (var out = ex.getResponseBody()) { out.write(body); }
        });
        server.createContext("/error", ex -> {
            byte[] body = "{\"error\":\"secret\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(503, body.length);
            try (var out = ex.getResponseBody()) { out.write(body); }
        });
        server.start();
        port = server.getAddress().getPort();
        configs = new PartnerConnections();
        var connection = new PartnerConnections.Connection();
        connection.setBaseUrl("http://127.0.0.1:" + port);
        connection.setBearerToken("testing-token");
        configs.getConnections().put("local", connection);
        http = new HttpAdapter(json, configs);
        gateway = new SupplierCommerceGateway(json, new MappingEngine(json), http, mock(McpAdapter.class));
    }
    @AfterEach void teardown() { if (server != null) server.stop(0); }

    @Test void restEndToEndWithRequestAndResponseMappings() {
        gateway.publish("""
          {"supplierId":"local-rest","version":1,"operations":{"createCart":{
          "route":{"transport":"REST","connection":"local","path":"/carts","action":"POST"},
          "requestMapping":{"items":{"path":"$.items[*]","each":{
             "sku":{"path":"$.externalVariantId"},"qty":{"path":"$.quantity"}}}},
          "responseMapping":{"externalCartId":{"path":"$.cart_id","required":true}}
          }}}
          """);
        gateway.activate("local-rest", 1);
        JsonNode canonical = json.readTree("""
          {"items":[{"externalVariantId":"SKU-42","quantity":2}]}
          """);
        var result = gateway.execute("local-rest", "createCart", canonical);
        assertEquals(1, result.mappingVersion());
        assertEquals("C-99", result.response().path("externalCartId").asText());
        assertEquals("Bearer testing-token", seenAuthorization.get());
        assertEquals("SKU-42", json.readTree(seenBody.get()).at("/items/0/sku").asText());
        assertEquals(2, json.readTree(seenBody.get()).at("/items/0/qty").intValue());
    }

    @Test void graphqlMutationChecksData() {
        var route = new SupplierCommerceGateway.Route(GRAPHQL, "local", "/graphql", "createCart");
        JsonNode response = http.graphql(route, json.readTree("""
          {"query":"mutation { createCart { id status } }"}
          """));
        assertEquals("G-77", response.at("/createCart/id").asText());
        assertTrue(seenBody.get().contains("mutation"));
    }

    @Test void restGetEncodesPrimitiveQueryParametersAndOmitsNulls() {
        var route = new SupplierCommerceGateway.Route(REST, "local", "/carts", "GET");
        http.rest(route, json.readTree("""
            {"sku":"SKU 42&x","quantity":2,"available":true,"optional":null}
            """));
        assertEquals("sku=SKU+42%26x&quantity=2&available=true", seenQuery.get());
    }

    @Test void restGetRejectsCompositeQueryParameters() {
        var route = new SupplierCommerceGateway.Route(REST, "local", "/carts", "GET");
        for (String query : List.of("{\"items\":[]}", "{\"filter\":{}}")) {
            assertEquals("INVALID_REQUEST", assertThrows(CommerceException.class,
                () -> http.rest(route, json.readTree(query))).code());
        }
    }

    @Test void restHttpErrorIsNotSilentlyMapped() {
        var route = new SupplierCommerceGateway.Route(REST, "local", "/error", "POST");
        var ex = assertThrows(CommerceException.class,
            () -> http.rest(route, json.readTree("{}")));
        assertEquals("HTTP_ERROR", ex.code());
        assertEquals(503, ex.httpStatus());
        assertFalse(ex.getMessage().contains("secret"));
    }

    @Test void mcpPrefersStructuredContentAndMapsErrors() {
        McpSyncClient sdkClient = mock(McpSyncClient.class);
        var ok = new McpSchema.CallToolResult(List.of(), false, Map.of("cart", Map.of("id", "MCP-5")), Map.of());
        when(sdkClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(ok);
        var adapter = spy(new McpAdapter(configs, json));
        var route = new SupplierCommerceGateway.Route(UCP_MCP, "local", "/mcp", "create_cart");
        doReturn(sdkClient).when(adapter).client(route);
        assertEquals("MCP-5", adapter.call(route, json.readTree("{\"cart\":{}}"))
            .at("/cart/id").asText());
        verify(sdkClient).callTool(argThat((McpSchema.CallToolRequest req) -> req.name().equals("create_cart")));
        when(sdkClient.callTool(any(McpSchema.CallToolRequest.class)))
            .thenReturn(new McpSchema.CallToolResult(List.of(), true, null, Map.of()));
        assertEquals("MCP_TOOL_ERROR", assertThrows(CommerceException.class,
            () -> adapter.call(route, json.readTree("{}"))).code());
    }

    @Test void graphqlGatewayEndToEnd() {
        gateway.publish("""
          {"supplierId":"local-graphql","version":1,"operations":{"createCart":{
            "route":{"transport":"GRAPHQL","connection":"local","path":"/graphql","action":"createCart"},
            "requestMapping":{
              "query":{"literal":"mutation CreateCart($items: [CartItemInput!]!) { createCart(items: $items) { id status } }"},
              "variables":{"path":"$","fields":{"items":{"path":"$.items[*]","each":{
                "variantId":{"path":"$.externalVariantId"},
                "quantity":{"path":"$.quantity"}
              }}}}
            },
            "responseMapping":{"externalCartId":{"path":"$.createCart.id","required":true}}
          }}}
          """);
        gateway.activate("local-graphql", 1);
        var cart = json.readTree("""
            {"items":[{"externalVariantId":"VAR-1", "quantity":3}]}
            """);
        var result = gateway.execute("local-graphql", "createCart", cart);
        assertEquals("G-77", result.response().path("externalCartId").asText());
        assertEquals("VAR-1", json.readTree(seenBody.get()).at("/variables/items/0/variantId").asText());
    }

    @Test void disallowsUntrustedOutboundConnection() {
        var route = new SupplierCommerceGateway.Route(REST, "unregistered", "/carts", "POST");
        var error = assertThrows(CommerceException.class,
            () -> http.rest(route, json.readTree("{}")));
        assertEquals("CONNECTION_UNKNOWN", error.code());
    }

}
