package com.example.commerce.mapping;

import com.example.commerce.CommerceException;
import com.example.commerce.integration.HttpAdapter;
import com.example.commerce.integration.McpAdapter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Holds versioned supplier mappings and executes operations: map request, call transport, map response. */
@Service
public class SupplierCommerceGateway implements ApplicationRunner {
    public enum Transport { REST, GRAPHQL, UCP_MCP }
    public record Route(Transport transport, String connection, String path, String action) {}
    public record Operation(Route route, Map<String, MappingEngine.Rule> request,
                            Map<String, MappingEngine.Rule> response) {}
    public record SupplierVersion(String supplierId, int version, Map<String, Operation> operations) {
        public Operation operation(String name) {
            Operation result = operations.get(name);
            if (result == null) throw new CommerceException("Operação não publicada: " + supplierId + "/" + name);
            return result;
        }
    }
    public record GatewayResult(String supplierId, int mappingVersion, String operation, JsonNode response) {}

    private final Map<String, Map<Integer, SupplierVersion>> versions = new ConcurrentHashMap<>();
    private final Map<String, Integer> active = new ConcurrentHashMap<>();
    private final JsonMapper mapper;
    private final MappingEngine engine;
    private final HttpAdapter http;
    private final McpAdapter mcp;
    private final Map<String, JsonNode> platforms;

    public SupplierCommerceGateway(JsonMapper mapper, MappingEngine engine, HttpAdapter http, McpAdapter mcp) {
        this.mapper = mapper; this.engine = engine; this.http = http; this.mcp = mcp;
        this.platforms = loadPlatforms();
    }

    @Override public void run(ApplicationArguments args) throws Exception {
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath*:suppliers/*.json");
        for (var resource : resources) {
            try (var in = resource.getInputStream()) {
                var version = publish(mapper.readTree(in));
                activate(version.supplierId(), version.version());
            }
        }
    }

    public GatewayResult execute(String supplier, String operation, JsonNode canonicalRequest) {
        return executePinned(active(supplier), operation, canonicalRequest);
    }
    public GatewayResult executeVersion(String supplier, int version, String operation, JsonNode canonicalRequest) {
        return executePinned(version(supplier, version), operation, canonicalRequest);
    }
    private GatewayResult executePinned(SupplierVersion version, String operation, JsonNode input) {
        // Pin the SAME immutable version for request mapping and response mapping.
        Operation op = version.operation(operation);
        JsonNode outgoing = engine.map(input, op.request());
        JsonNode incoming = switch (op.route().transport()) {
            case REST -> http.rest(op.route(), outgoing);
            case GRAPHQL -> http.graphql(op.route(), outgoing);
            case UCP_MCP -> mcp.call(op.route(), outgoing);
        };
        if (incoming == null) throw new CommerceException("Resposta do parceiro nula");
        return new GatewayResult(version.supplierId(), version.version(), operation, engine.map(incoming, op.response()));
    }

    public SupplierVersion publish(String json) { return publish(mapper.readTree(json)); }
    public synchronized SupplierVersion publish(JsonNode definition) {
        SupplierVersion v = compile(definition); // compile before modifying state
        var supplierVersions = versions.computeIfAbsent(v.supplierId(), ignored -> new ConcurrentHashMap<>());
        if (supplierVersions.putIfAbsent(v.version(), v) != null)
            throw new CommerceException("Versão já publicada: " + v.supplierId() + "/" + v.version());
        return v;
    }
    public synchronized void activate(String supplierId, int version) {
        active.put(supplierId, version(supplierId, version).version());
    }
    public SupplierVersion active(String supplierId) {
        Integer n = active.get(supplierId);
        if (n == null) throw new CommerceException("Fornecedor não ativo: " + supplierId);
        return version(supplierId, n);
    }
    public SupplierVersion version(String supplierId, int version) {
        SupplierVersion v = versions.getOrDefault(supplierId, Map.of()).get(version);
        if (v == null) throw new CommerceException("Versão não encontrada: " + supplierId + "/" + version);
        return v;
    }

    private SupplierVersion compile(JsonNode definition) {
        if (definition == null || !definition.isObject()) throw new CommerceException("Documento inválido");
        definition = withPlatform(definition);
        String supplierId = requiredText(definition, "supplierId");
        if (!supplierId.matches("[a-zA-Z0-9_-]{1,80}")) throw new CommerceException("supplierId inválido");
        JsonNode ver = definition.get("version");
        if (ver == null || !ver.isIntegralNumber() || !ver.canConvertToInt() || ver.intValue() <= 0)
            throw new CommerceException("version inválida");
        JsonNode operations = definition.path("operations");
        if (!operations.isObject() || operations.isEmpty()) throw new CommerceException("operations vazio");
        var compiled = new LinkedHashMap<String, Operation>();
        operations.forEachEntry((name, spec) -> {
            if (name.isBlank() || !spec.isObject()) throw new CommerceException("Operação inválida: " + name);
            JsonNode r = spec.path("route");
            String transport = requiredText(r, "transport");
            Transport parsed;
            try { parsed = Transport.valueOf(transport); }
            catch (IllegalArgumentException ex) { throw new CommerceException("Transporte inválido: " + transport, ex); }
            String connection = requiredText(r, "connection");
            String path = requiredText(r, "path");
            if (!path.startsWith("/") || path.startsWith("//") || path.contains("..") || path.contains("?") || path.contains("#"))
                throw new CommerceException("path HTTP inseguro: " + path);
            String action = requiredText(r, "action");
            JsonNode request = spec.get("requestMapping"), response = spec.get("responseMapping");
            if (request == null || response == null) throw new CommerceException("Mappings incompletos: " + name);
            compiled.put(name, new Operation(new Route(parsed, connection, path, action),
                engine.compile(request), engine.compile(response)));
        });
        return new SupplierVersion(supplierId, ver.intValue(), Map.copyOf(compiled));
    }

    /** Expands {"platform","connection"}: template operations bound to the supplier's connection; supplier operations override them field by field. */
    private JsonNode withPlatform(JsonNode definition) {
        if (!definition.has("platform")) return definition;
        String platform = requiredText(definition, "platform");
        JsonNode template = platforms.get(platform);
        if (template == null) throw new CommerceException("Plataforma desconhecida: " + platform);
        String connection = requiredText(definition, "connection");
        ObjectNode operations = (ObjectNode) template.get("operations").deepCopy();
        operations.forEachEntry((name, op) -> ((ObjectNode) op.get("route")).put("connection", connection));
        definition.path("operations").forEachEntry((name, override) -> {
            if (!override.isObject()) throw new CommerceException("Operação inválida: " + name);
            if (operations.get(name) instanceof ObjectNode base) base.setAll((ObjectNode) override.deepCopy());
            else operations.set(name, override.deepCopy());
        });
        ObjectNode expanded = (ObjectNode) definition.deepCopy();
        expanded.set("operations", operations);
        return expanded;
    }

    private Map<String, JsonNode> loadPlatforms() {
        var loaded = new HashMap<String, JsonNode>();
        try {
            for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:platforms/*.json")) {
                try (var in = resource.getInputStream()) {
                    JsonNode template = mapper.readTree(in);
                    if (!template.path("operations").isObject() || template.get("operations").isEmpty())
                        throw new CommerceException("Template sem operations: " + resource.getFilename());
                    template.get("operations").forEachEntry((name, op) -> {
                        if (!op.path("route").isObject()) throw new CommerceException("Template sem route: " + name);
                    });
                    loaded.put(requiredText(template, "platform"), template);
                }
            }
        } catch (IOException e) {
            throw new CommerceException("Falha ao carregar templates de plataforma", e);
        }
        return Map.copyOf(loaded);
    }

    private static String requiredText(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank())
            throw new CommerceException("Texto obrigatório: " + field);
        return value.asText();
    }
}
