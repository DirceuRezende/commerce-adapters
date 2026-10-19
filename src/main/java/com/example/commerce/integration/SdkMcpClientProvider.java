package com.example.commerce.integration;

import com.example.commerce.mapping.SupplierMappingRegistry.Route;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One MCP session per trusted connection + endpoint. Never share user-scoped tokens across users. */
@Component
public class SdkMcpClientProvider implements McpClientProvider {
    private final PartnerConnections connections;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    public SdkMcpClientProvider(PartnerConnections connections) { this.connections = connections; }

    @Override public McpSyncClient get(Route route) {
        PartnerConnections.Connection c = connections.lookup(route);
        return clients.computeIfAbsent(route.connection() + "|" + route.path(), ignored -> create(route, c));
    }

    private McpSyncClient create(Route route, PartnerConnections.Connection c) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        HttpJsonExchange.applyAuth(requestBuilder, c);
        var transport = HttpClientStreamableHttpTransport.builder(c.getBaseUrl())
            .endpoint(route.path())
            .requestBuilder(requestBuilder)
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .maxResponseSize(2_000_000)
            .build();
        McpSyncClient client = McpClient.sync(transport)
            .requestTimeout(c.getTimeout())
            .build();
        try {
            client.initialize(); // negotiates protocol/capabilities; supports JSON and SSE transport
            return client;
        } catch (RuntimeException e) {
            client.closeGracefully();
            throw new IntegrationException("MCP_INIT_FAILED", null, "Falha na inicialização MCP", e);
        }
    }

    @PreDestroy public void close() {
        for (McpSyncClient client : clients.values()) client.closeGracefully();
        clients.clear();
    }
}
