package com.example.commerce.integration;

import com.example.commerce.mapping.SupplierMappingRegistry.Route;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
public class HttpJsonExchange {
    private static final int MAX_RESPONSE_BYTES = 2_000_000;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper mapper;
    private final PartnerConnections connections;

    public HttpJsonExchange(JsonMapper mapper, PartnerConnections connections) {
        this.mapper = mapper; this.connections = connections;
    }

    public JsonNode execute(Route route, String method, JsonNode body, boolean queryParams) {
        PartnerConnections.Connection c = connections.lookup(route);
        URI uri = connections.uri(route);
        if (queryParams) uri = withQuery(uri, body);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(c.getTimeout())
            .header("Accept", "application/json");
        applyAuth(b, c);
        String verb = method.toUpperCase(Locale.ROOT);
        switch (verb) {
            case "GET" -> b.GET();
            case "DELETE" -> b.DELETE();
            case "POST", "PUT", "PATCH" -> {
                b.header("Content-Type", "application/json");
                b.method(verb, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            }
            default -> throw new IntegrationException("METHOD_UNSUPPORTED", null, "Método HTTP não permitido");
        }
        try {
            HttpResponse<java.io.InputStream> response = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES)
                    throw new IntegrationException("BODY_TOO_LARGE", response.statusCode(), "Resposta excede limite");
                int status = response.statusCode();
                if (status < 200 || status >= 300)
                    throw new IntegrationException("HTTP_ERROR", status, "Parceiro retornou HTTP " + status);
                if (bytes.length == 0) throw new IntegrationException("EMPTY_RESPONSE", status, "Resposta sem corpo JSON");
                try { return mapper.readTree(new String(bytes, StandardCharsets.UTF_8)); }
                catch (RuntimeException e) {
                    throw new IntegrationException("INVALID_JSON", status, "JSON de resposta inválido", e);
                }
            }
        } catch (IOException e) {
            throw new IntegrationException("NETWORK_ERROR", null, "Falha na comunicação com fornecedor", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntegrationException("INTERRUPTED", null, "Requisição interrompida", e);
        }
    }

    public static void applyAuth(HttpRequest.Builder b, PartnerConnections.Connection config) {
        if (config.getBearerToken() != null && !config.getBearerToken().isBlank())
            b.header("Authorization", "Bearer " + config.getBearerToken());
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            String name = config.getApiKeyHeader();
            if (name == null || !name.matches("[A-Za-z0-9-]{1,60}") ||
                Set.of("host", "authorization", "content-length", "connection").contains(name.toLowerCase(Locale.ROOT)))
                throw new IntegrationException("CONNECTION_INVALID", null, "Nome de header de API Key proibido");
            b.header(name, config.getApiKey());
        }
    }

    private static URI withQuery(URI uri, JsonNode query) {
        if (!query.isObject()) throw new IntegrationException("INVALID_REQUEST", null, "Query params precisam ser objeto");
        List<String> params = new ArrayList<>();
        query.forEachEntry((name, value) -> {
            if (value.isObject() || value.isArray()) throw new IntegrationException("INVALID_REQUEST", null, "Query param composto: " + name);
            if (!value.isNull()) params.add(URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(value.asText(), StandardCharsets.UTF_8));
        });
        if (params.isEmpty()) return uri;
        return URI.create(uri + "?" + String.join("&", params));
    }
}
