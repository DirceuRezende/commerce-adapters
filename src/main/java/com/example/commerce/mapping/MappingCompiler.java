package com.example.commerce.mapping;

import com.jayway.jsonpath.JsonPath;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.*;

@Component
public class MappingCompiler {
    public record Transform(String name, JsonNode args) {}
    public record Rule(JsonPath path, boolean hasLiteral, JsonNode literal, boolean required,
                       List<Transform> transforms, Map<String, Rule> fields, Map<String, Rule> each) {}

    private static final Set<String> TRANSFORMS = Set.of("trim", "uppercase", "greaterThan", "decimalToMinor");

    public Map<String, Rule> compile(JsonNode rules) { return compile(rules, 0); }

    private Map<String, Rule> compile(JsonNode rules, int depth) {
        if (depth > 20 || rules == null || !rules.isObject() || rules.isEmpty())
            throw new MappingException("Regras inválidas ou profundidade excessiva");
        Map<String, Rule> compiled = new LinkedHashMap<>();
        rules.forEachEntry((key, r) -> {
            if (key.isBlank() || !r.isObject()) throw new MappingException("Regra inválida: " + key);
            boolean hasPath = r.has("path");
            boolean hasLiteral = r.has("literal");
            if (hasPath == hasLiteral) throw new MappingException("Exige path XOR literal: " + key);
            JsonPath path = null;
            if (hasPath) {
                if (!r.get("path").isTextual() || !r.get("path").asText().startsWith("$"))
                    throw new MappingException("JsonPath inválido: " + key);
                try { path = JsonPath.compile(r.get("path").asText()); }
                catch (RuntimeException ex) { throw new MappingException("JsonPath inválido: " + key, ex); }
            }
            if (r.has("required") && !r.get("required").isBoolean())
                throw new MappingException("required deve ser booleano: " + key);
            if (r.has("each") && r.has("fields"))
                throw new MappingException("each/fields mutuamente exclusivos: " + key);
            List<Transform> transforms = new ArrayList<>();
            JsonNode ts = r.path("transforms");
            if (!ts.isMissingNode()) {
                if (!ts.isArray()) throw new MappingException("transforms deve ser array: " + key);
                for (JsonNode t : ts) {
                    if (!t.isObject() || !t.path("name").isTextual()) throw new MappingException("Transform inválida");
                    String name = t.get("name").asText();
                    if (!TRANSFORMS.contains(name)) throw new MappingException("Transform desconhecida: " + name);
                    transforms.add(new Transform(name, t.get("args")));
                }
            }
            compiled.put(key, new Rule(path, hasLiteral, r.get("literal"), r.path("required").asBoolean(false),
                List.copyOf(transforms), r.has("fields") ? compile(r.get("fields"), depth+1) : Map.of(),
                r.has("each") ? compile(r.get("each"), depth+1) : Map.of()));
        });
        return Collections.unmodifiableMap(compiled);
    }
}
