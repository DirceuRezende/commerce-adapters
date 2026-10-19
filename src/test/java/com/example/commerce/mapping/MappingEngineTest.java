package com.example.commerce.mapping;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class MappingEngineTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final MappingCompiler compiler = new MappingCompiler();
    private final MappingEngine engine = new MappingEngine(json);

    @Test void trimsAndUppercasesTextInOrderRegardlessOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            JsonNode result = map("""
                {"sku":"  item-42  "}
                """, """
                {"sku":{"path":"$.sku","transforms":[{"name":"trim"},{"name":"uppercase"}]}}
                """);
            assertEquals("ITEM-42", result.path("sku").asText());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test void comparesNumbersWithStrictGreaterThan() {
        String rules = """
            {"available":{"path":"$.quantity","transforms":[{"name":"greaterThan","args":{"value":2}}]}}
            """;
        assertTrue(map("{\"quantity\":3}", rules).path("available").booleanValue());
        assertFalse(map("{\"quantity\":2}", rules).path("available").booleanValue());
        assertFalse(map("{\"quantity\":1}", rules).path("available").booleanValue());
    }

    @Test void convertsDecimalAmountsUsingCurrencyScale() {
        JsonNode result = map("{\"price\":12.34,\"yen\":1234}", """
            {"cents":{"path":"$.price","transforms":[{"name":"decimalToMinor","args":{"currency":"BRL"}}]},
             "yen":{"path":"$.yen","transforms":[{"name":"decimalToMinor","args":{"currency":"JPY"}}]}}
            """);
        assertTrue(result.path("cents").isIntegralNumber());
        assertEquals(1234, result.path("cents").intValue());
        assertEquals(1234, result.path("yen").intValue());
    }

    @Test void rejectsFractionalMinorUnitsRatherThanRounding() {
        assertThrows(MappingException.class, () -> map("{\"price\":12.345}", """
            {"cents":{"path":"$.price","transforms":[{"name":"decimalToMinor","args":{"currency":"BRL"}}]}}
            """));
    }

    @Test void rejectsNonTextInputForTrim() {
        assertThrows(MappingException.class, () -> map("{\"sku\":42}", """
            {"sku":{"path":"$.sku","transforms":[{"name":"trim"}]}}
            """));
    }

    private JsonNode map(String source, String rules) {
        return engine.map(json.readTree(source), compiler.compile(json.readTree(rules)));
    }
}
