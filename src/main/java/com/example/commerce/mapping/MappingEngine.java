package com.example.commerce.mapping;

import com.example.commerce.CommerceException;
import com.jayway.jsonpath.*;
import com.jayway.jsonpath.spi.json.Jackson3JsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.Jackson3MappingProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.*;

@Component
public class MappingEngine {
    public record Transform(String name, JsonNode args) {}
    public record Rule(JsonPath path, boolean hasLiteral, JsonNode literal, boolean required,
                       List<Transform> transforms, Map<String, Rule> fields, Map<String, Rule> each) {}

    private static final Set<String> TRANSFORMS = Set.of("trim", "uppercase", "greaterThan", "decimalToMinor");

    public Map<String, Rule> compile(JsonNode rules) { return compile(rules, 0); }

    private Map<String, Rule> compile(JsonNode rules, int depth) {
        if (depth > 20 || rules == null || !rules.isObject() || rules.isEmpty())
            throw new CommerceException("Regras inválidas ou profundidade excessiva");
        Map<String, Rule> compiled = new LinkedHashMap<>();
        rules.forEachEntry((key, r) -> {
            if (key.isBlank() || !r.isObject()) throw new CommerceException("Regra inválida: " + key);
            boolean hasPath = r.has("path");
            boolean hasLiteral = r.has("literal");
            if (hasPath == hasLiteral) throw new CommerceException("Exige path XOR literal: " + key);
            JsonPath path = null;
            if (hasPath) {
                if (!r.get("path").isTextual() || !r.get("path").asText().startsWith("$"))
                    throw new CommerceException("JsonPath inválido: " + key);
                try { path = JsonPath.compile(r.get("path").asText()); }
                catch (RuntimeException ex) { throw new CommerceException("JsonPath inválido: " + key, ex); }
            }
            if (r.has("required") && !r.get("required").isBoolean())
                throw new CommerceException("required deve ser booleano: " + key);
            if (r.has("each") && r.has("fields"))
                throw new CommerceException("each/fields mutuamente exclusivos: " + key);
            List<Transform> transforms = new ArrayList<>();
            JsonNode ts = r.path("transforms");
            if (!ts.isMissingNode()) {
                if (!ts.isArray()) throw new CommerceException("transforms deve ser array: " + key);
                for (JsonNode t : ts) {
                    if (!t.isObject() || !t.path("name").isTextual()) throw new CommerceException("Transform inválida");
                    String name = t.get("name").asText();
                    if (!TRANSFORMS.contains(name)) throw new CommerceException("Transform desconhecida: " + name);
                    transforms.add(new Transform(name, t.get("args")));
                }
            }
            compiled.put(key, new Rule(path, hasLiteral, r.get("literal"), r.path("required").asBoolean(false),
                List.copyOf(transforms), r.has("fields") ? compile(r.get("fields"), depth+1) : Map.of(),
                r.has("each") ? compile(r.get("each"), depth+1) : Map.of()));
        });
        return Collections.unmodifiableMap(compiled);
    }

    private final JsonMapper mapper;
    private final Configuration configuration;

    public MappingEngine(JsonMapper mapper) {
        this.mapper = mapper;
        configuration = Configuration.builder().jsonProvider(new Jackson3JsonNodeJsonProvider(mapper))
            .mappingProvider(new Jackson3MappingProvider(mapper)).build();
    }

    public ObjectNode map(JsonNode source, Map<String, Rule> compiled) {
        if (source == null) throw new CommerceException("Origem nula");
        DocumentContext context = JsonPath.using(configuration).parse(mapper.writeValueAsString(source));
        ObjectNode output = mapper.createObjectNode();
        for (var entry : compiled.entrySet()) {
            JsonNode value = evaluate(context, entry.getValue());
            if (value != null) output.set(entry.getKey(), value);
        }
        return output;
    }

    private JsonNode evaluate(DocumentContext context, Rule rule) {
        JsonNode value;
        if (rule.hasLiteral()) value = rule.literal().deepCopy();
        else {
            try { value = context.read(rule.path(), JsonNode.class); }
            catch (PathNotFoundException ex) {
                if (rule.required()) throw new CommerceException("Path obrigatório ausente: " + rule.path().getPath(), ex);
                return null;
            }
        }
        if (value == null || value.isMissingNode()) {
            if (rule.required()) throw new CommerceException("Valor obrigatório ausente");
            return null;
        }
        if (!rule.each().isEmpty()) {
            if (!value.isArray() || value.size() > 10_000) throw new CommerceException("Array inválido/excessivo");
            ArrayNode array = mapper.createArrayNode();
            for (JsonNode child : value) array.add(map(child, rule.each()));
            value = array;
        } else if (!rule.fields().isEmpty()) {
            if (!value.isObject()) throw new CommerceException("Esperado objeto para fields");
            value = map(value, rule.fields());
        }
        for (Transform t : rule.transforms()) value = transform(value, t);
        return value;
    }

    private static JsonNode transform(JsonNode v, Transform t) {
        try {
            return switch (t.name()) {
                case "trim" -> StringNode.valueOf(asText(v).trim());
                case "uppercase" -> StringNode.valueOf(asText(v).toUpperCase(java.util.Locale.ROOT));
                case "greaterThan" -> BooleanNode.valueOf(asNumber(v).compareTo(asNumber(t.args().path("value"))) > 0);
                case "decimalToMinor" -> {
                    String currency = asText(t.args().path("currency"));
                    int scale = Currency.getInstance(currency).getDefaultFractionDigits();
                    if (scale < 0) throw new CommerceException("Moeda sem escala: " + currency);
                    yield BigIntegerNode.valueOf(asNumber(v).movePointRight(scale).toBigIntegerExact());
                }
                default -> throw new CommerceException("Transform desconhecida: " + t.name());
            };
        } catch (RuntimeException ex) { throw new CommerceException("Transform falhou: " + t.name(), ex); }
    }
    private static String asText(JsonNode v) {
        if (v == null || !v.isTextual()) throw new CommerceException("Esperado texto");
        return v.asText();
    }
    private static BigDecimal asNumber(JsonNode v) {
        if (v == null || !v.isNumber()) throw new CommerceException("Esperado número");
        return v.decimalValue();
    }
}
