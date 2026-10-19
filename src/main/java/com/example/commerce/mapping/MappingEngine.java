package com.example.commerce.mapping;

import com.jayway.jsonpath.*;
import com.jayway.jsonpath.spi.json.Jackson3JsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.Jackson3MappingProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;
import static com.example.commerce.mapping.MappingCompiler.*;

@Component
public class MappingEngine {
    private final JsonMapper mapper;
    private final Configuration configuration;

    public MappingEngine(JsonMapper mapper) {
        this.mapper = mapper;
        configuration = Configuration.builder().jsonProvider(new Jackson3JsonNodeJsonProvider(mapper))
            .mappingProvider(new Jackson3MappingProvider(mapper)).build();
    }

    public ObjectNode map(JsonNode source, Map<String, Rule> compiled) {
        if (source == null) throw new MappingException("Origem nula");
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
                if (rule.required()) throw new MappingException("Path obrigatório ausente: " + rule.path().getPath(), ex);
                return null;
            }
        }
        if (value == null || value.isMissingNode()) {
            if (rule.required()) throw new MappingException("Valor obrigatório ausente");
            return null;
        }
        if (!rule.each().isEmpty()) {
            if (!value.isArray() || value.size() > 10_000) throw new MappingException("Array inválido/excessivo");
            ArrayNode array = mapper.createArrayNode();
            for (JsonNode child : value) array.add(map(child, rule.each()));
            value = array;
        } else if (!rule.fields().isEmpty()) {
            if (!value.isObject()) throw new MappingException("Esperado objeto para fields");
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
                    if (scale < 0) throw new MappingException("Moeda sem escala: " + currency);
                    yield BigIntegerNode.valueOf(asNumber(v).movePointRight(scale).toBigIntegerExact());
                }
                default -> throw new MappingException("Transform desconhecida: " + t.name());
            };
        } catch (RuntimeException ex) { throw new MappingException("Transform falhou: " + t.name(), ex); }
    }
    private static String asText(JsonNode v) {
        if (v == null || !v.isTextual()) throw new MappingException("Esperado texto");
        return v.asText();
    }
    private static BigDecimal asNumber(JsonNode v) {
        if (v == null || !v.isNumber()) throw new MappingException("Esperado número");
        return v.decimalValue();
    }
}
