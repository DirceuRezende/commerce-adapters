package com.example.commerce.integration;

import com.example.commerce.CommerceException;
import com.example.commerce.mapping.SupplierMappingRegistry.Route;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpRequest;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;

@Component
/** Maintains one application-scoped MCP session per trusted connection and endpoint. */
public class McpProtocolAdapter implements ProtocolAdapter {
    private final PartnerConnections connections;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    private final JsonMapper mapper;
    public McpProtocolAdapter(PartnerConnections connections, JsonMapper mapper) {
        this.connections = connections; this.mapper = mapper;
    }
    @Override public String transport() { return "UCP_MCP"; }

    @Override @SuppressWarnings("unchecked")
    public JsonNode execute(Route route, JsonNode arguments) {
        if (!arguments.isObject())
            throw new CommerceException("INVALID_MCP_ARGUMENTS", null, "tools/call exige objeto JSON");
        Map<String, Object> values = mapper.readValue(mapper.writeValueAsString(arguments), Map.class);
        try {
            var request = McpSchema.CallToolRequest.builder(route.action()).arguments(values).build();
            var result = client(route).callTool(request);
            if (result == null || Boolean.TRUE.equals(result.isError()))
                throw new CommerceException("MCP_TOOL_ERROR", null, "Ferramenta MCP retornou erro");
            if (result.structuredContent() != null) {
                JsonNode structured = mapper.valueToTree(result.structuredContent());
                if (structured.isObject()) return structured;
                throw new CommerceException("MCP_INVALID_RESULT", null, "structuredContent não é objeto JSON");
            }
            // Compatible fallback only when a text block contains parseable JSON object.
            for (var content : result.content()) {
                if (content instanceof McpSchema.TextContent text) {
                    try {
                        JsonNode parsed = mapper.readTree(text.text());
                        if (parsed.isObject()) return parsed;
                    } catch (RuntimeException ignored) { /* try next text content */ }
                }
            }
            throw new CommerceException("MCP_NO_STRUCTURED_JSON", null,
                "Ferramenta não retornou JSON estruturado mapeável");
        } catch (CommerceException ex) { throw ex; }
        catch (RuntimeException ex) {
            throw new CommerceException("MCP_CALL_FAILED", null, "Falha na chamada tools/call", ex);
        }
    }

    McpSyncClient client(Route route) {
        PartnerConnections.Connection c = connections.lookup(route);
        return clients.computeIfAbsent(route.connection() + "|" + route.path(), ignored -> createClient(route, c));
    }

    McpSyncClient createClient(Route route, PartnerConnections.Connection c) {
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
            throw new CommerceException("MCP_INIT_FAILED", null, "Falha na inicialização MCP", e);
        }
    }

    @PreDestroy public void close() {
        for (McpSyncClient client : clients.values()) client.closeGracefully();
        clients.clear();
    }
}
