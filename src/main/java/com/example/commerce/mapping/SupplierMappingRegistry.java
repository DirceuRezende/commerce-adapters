package com.example.commerce.mapping;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class SupplierMappingRegistry {
    public record Route(String transport, String connection, String path, String action) {}
    public record Operation(Route route, Map<String, MappingCompiler.Rule> request,
                            Map<String, MappingCompiler.Rule> response) {}
    public record SupplierVersion(String supplierId, int version, Map<String, Operation> operations) {
        public Operation operation(String name) {
            Operation result = operations.get(name);
            if (result == null) throw new MappingException("Operação não publicada: " + supplierId + "/" + name);
            return result;
        }
    }
    private record Snapshot(Map<String, Map<Integer, SupplierVersion>> versions,
                            Map<String, Integer> active) {}
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(Map.of(), Map.of()));
    private final JsonMapper mapper;
    private final MappingCompiler compiler;

    public SupplierMappingRegistry(JsonMapper mapper, MappingCompiler compiler) {
        this.mapper = mapper; this.compiler = compiler;
    }
    public synchronized SupplierVersion publish(String json) { return publish(mapper.readTree(json)); }
    public synchronized SupplierVersion publish(JsonNode definition) {
        SupplierVersion v = compile(definition); // compile before modifying active state
        Snapshot before = snapshot.get();
        var versions = new HashMap<>(before.versions());
        var supplierVersions = new HashMap<>(versions.getOrDefault(v.supplierId(), Map.of()));
        if (supplierVersions.putIfAbsent(v.version(), v) != null)
            throw new MappingException("Versão já publicada: " + v.supplierId() + "/" + v.version());
        versions.put(v.supplierId(), Map.copyOf(supplierVersions));
        snapshot.set(new Snapshot(Map.copyOf(versions), before.active()));
        return v;
    }
    public synchronized void activate(String supplierId, int version) {
        Snapshot before = snapshot.get();
        if (!before.versions().getOrDefault(supplierId, Map.of()).containsKey(version))
            throw new MappingException("Versão não publicada: " + supplierId + "/" + version);
        var active = new HashMap<>(before.active());
        active.put(supplierId, version);
        snapshot.set(new Snapshot(before.versions(), Map.copyOf(active)));
    }
    public SupplierVersion active(String supplierId) {
        Snapshot state = snapshot.get();
        Integer n = state.active().get(supplierId);
        if (n == null) throw new MappingException("Fornecedor não ativo: " + supplierId);
        return state.versions().get(supplierId).get(n);
    }
    public SupplierVersion version(String supplierId, int version) {
        SupplierVersion v = snapshot.get().versions().getOrDefault(supplierId, Map.of()).get(version);
        if (v == null) throw new MappingException("Versão não encontrada: " + supplierId + "/" + version);
        return v;
    }
    private SupplierVersion compile(JsonNode definition) {
        if (definition == null || !definition.isObject()) throw new MappingException("Documento inválido");
        String supplierId = requiredText(definition, "supplierId");
        if (!supplierId.matches("[a-zA-Z0-9_-]{1,80}")) throw new MappingException("supplierId inválido");
        JsonNode ver = definition.get("version");
        if (ver == null || !ver.isIntegralNumber() || !ver.canConvertToInt() || ver.intValue() <= 0)
            throw new MappingException("version inválida");
        JsonNode operations = definition.path("operations");
        if (!operations.isObject() || operations.isEmpty()) throw new MappingException("operations vazio");
        var compiled = new LinkedHashMap<String,Operation>();
        operations.forEachEntry((name, spec) -> {
            if (name.isBlank() || !spec.isObject()) throw new MappingException("Operação inválida: " + name);
            JsonNode r = spec.path("route");
            String transport = requiredText(r, "transport");
            if (!Set.of("REST", "GRAPHQL", "UCP_MCP").contains(transport))
                throw new MappingException("Transporte inválido: " + transport);
            String connection = requiredText(r, "connection");
            String path = requiredText(r, "path");
            if (!path.startsWith("/") || path.startsWith("//") || path.contains("..") || path.contains("?") || path.contains("#"))
                throw new MappingException("path HTTP inseguro: " + path);
            String action = requiredText(r, "action");
            JsonNode request = spec.get("requestMapping"), response = spec.get("responseMapping");
            if (request == null || response == null) throw new MappingException("Mappings incompletos: " + name);
            compiled.put(name, new Operation(new Route(transport, connection, path, action),
                compiler.compile(request), compiler.compile(response)));
        });
        return new SupplierVersion(supplierId, ver.intValue(), Map.copyOf(compiled));
    }
    private static String requiredText(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank())
            throw new MappingException("Texto obrigatório: " + field);
        return value.asText();
    }
}
