package com.example.commerce.integration;

import com.example.commerce.mapping.SupplierMappingRegistry.Route;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;

@Component
public class McpProtocolAdapter implements ProtocolAdapter {
    private final McpClientProvider provider;
    private final JsonMapper mapper;
    public McpProtocolAdapter(McpClientProvider provider, JsonMapper mapper) {
        this.provider = provider; this.mapper = mapper;
    }
    @Override public String transport() { return "UCP_MCP"; }

    @Override @SuppressWarnings("unchecked")
    public JsonNode execute(Route route, JsonNode arguments) {
        if (!arguments.isObject())
            throw new IntegrationException("INVALID_MCP_ARGUMENTS", null, "tools/call exige objeto JSON");
        Map<String, Object> values = mapper.readValue(mapper.writeValueAsString(arguments), Map.class);
        try {
            var request = McpSchema.CallToolRequest.builder(route.action()).arguments(values).build();
            var result = provider.get(route).callTool(request);
            if (result == null || Boolean.TRUE.equals(result.isError()))
                throw new IntegrationException("MCP_TOOL_ERROR", null, "Ferramenta MCP retornou erro");
            if (result.structuredContent() != null) {
                JsonNode structured = mapper.valueToTree(result.structuredContent());
                if (structured.isObject()) return structured;
                throw new IntegrationException("MCP_INVALID_RESULT", null, "structuredContent não é objeto JSON");
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
            throw new IntegrationException("MCP_NO_STRUCTURED_JSON", null,
                "Ferramenta não retornou JSON estruturado mapeável");
        } catch (IntegrationException ex) { throw ex; }
        catch (RuntimeException ex) {
            throw new IntegrationException("MCP_CALL_FAILED", null, "Falha na chamada tools/call", ex);
        }
    }
}
