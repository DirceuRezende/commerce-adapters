package com.example.commerce.integration;

import com.example.commerce.mapping.SupplierCommerceGateway.Route;
import static com.example.commerce.mapping.SupplierCommerceGateway.Transport.UCP_MCP;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpAdapterTest {
    @Test void reusesSessionsPerConnectionAndEndpointAndClosesThem() {
        var connections = new PartnerConnections();
        var connection = new PartnerConnections.Connection();
        connection.setBaseUrl("https://example.invalid");
        connections.getConnections().put("supplier", connection);
        var adapter = spy(new McpAdapter(connections, JsonMapper.builder().build()));
        var firstRoute = new Route(UCP_MCP, "supplier", "/mcp", "create_cart");
        var sameSession = new Route(UCP_MCP, "supplier", "/mcp", "search_catalog");
        var otherEndpoint = new Route(UCP_MCP, "supplier", "/other", "create_cart");
        var firstClient = mock(McpSyncClient.class);
        var secondClient = mock(McpSyncClient.class);
        doReturn(firstClient).when(adapter).createClient(firstRoute, connection);
        doReturn(secondClient).when(adapter).createClient(otherEndpoint, connection);

        assertSame(firstClient, adapter.client(firstRoute));
        assertSame(firstClient, adapter.client(sameSession));
        assertSame(secondClient, adapter.client(otherEndpoint));
        verify(adapter, times(1)).createClient(firstRoute, connection);

        adapter.close();
        adapter.close();
        verify(firstClient, times(1)).closeGracefully();
        verify(secondClient, times(1)).closeGracefully();
    }
}
