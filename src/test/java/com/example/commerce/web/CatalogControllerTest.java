package com.example.commerce.web;

import com.example.commerce.CommerceException;
import com.example.commerce.integration.HttpAdapter;
import com.example.commerce.integration.McpAdapter;
import com.example.commerce.mapping.MappingEngine;
import com.example.commerce.mapping.SupplierCommerceGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.example.commerce.mapping.SupplierCommerceGateway.Transport.UCP_MCP;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogControllerTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final McpAdapter mcp = mock(McpAdapter.class);
    private final SupplierCommerceGateway gateway =
        new SupplierCommerceGateway(json, new MappingEngine(json), mock(HttpAdapter.class), mcp);
    private MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        try (var in = new ClassPathResource("suppliers/hubii.json").getInputStream()) {
            var version = gateway.publish(json.readTree(in));
            gateway.activate(version.supplierId(), version.version());
        }
        mvc = MockMvcBuilders.standaloneSetup(new CatalogController(gateway, json)).build();
    }

    @Test void searchesCatalogThroughUcpMcpAndReturnsCanonicalProducts() throws Exception {
        when(mcp.call(any(), any())).thenReturn(json.readTree("""
            {"ucp":{"status":"success"},"products":[{"id":"gid://shopify/Product/1","title":"Ração",
              "price_range":{"min":{"amount":46124,"currency":"BRL"},"max":{"amount":46124,"currency":"BRL"}}}]}
            """));

        mvc.perform(get("/suppliers/hubii/catalog/search").param("query", "ração").param("limit", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.products[0].id").value("gid://shopify/Product/1"))
            .andExpect(jsonPath("$.products[0].name").value("Ração"))
            .andExpect(jsonPath("$.products[0].priceMinor").value(46124))
            .andExpect(jsonPath("$.products[0].currency").value("BRL"));

        verify(mcp).call(argThat(route -> route.transport() == UCP_MCP && route.action().equals("search_catalog")),
            argThat((JsonNode args) -> args.at("/catalog/query").asText().equals("ração")
                && args.at("/catalog/pagination/limit").intValue() == 5
                && args.at("/meta/ucp-agent/profile").isTextual()));
    }

    @Test void mapsPartnerFailureToBadGateway() throws Exception {
        when(mcp.call(any(), any())).thenThrow(new CommerceException("MCP_TOOL_ERROR", null, "Ferramenta MCP retornou erro"));

        mvc.perform(get("/suppliers/hubii/catalog/search").param("query", "camiseta"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.title").value("MCP_TOOL_ERROR"));
    }

    @Test void rejectsUnknownSupplierAsBadRequest() throws Exception {
        mvc.perform(get("/suppliers/unknown/catalog/search").param("query", "camiseta"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("MAPPING_ERROR"));
        verifyNoInteractions(mcp);
    }
}
