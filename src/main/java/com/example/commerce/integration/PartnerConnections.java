package com.example.commerce.integration;

import com.example.commerce.CommerceException;
import com.example.commerce.mapping.SupplierMappingRegistry.Route;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "commerce")
public class PartnerConnections {
    private Map<String, Connection> connections = new HashMap<>();
    public Map<String, Connection> getConnections() { return connections; }
    public void setConnections(Map<String, Connection> connections) { this.connections = connections; }

    public static class Connection {
        private String baseUrl;
        private String bearerToken;
        private String apiKey;
        private String apiKeyHeader = "X-API-Key";
        private Duration timeout = Duration.ofSeconds(10);
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String s) { baseUrl = s; }
        public String getBearerToken() { return bearerToken; }
        public void setBearerToken(String s) { bearerToken = s; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String s) { apiKey = s; }
        public String getApiKeyHeader() { return apiKeyHeader; }
        public void setApiKeyHeader(String s) { apiKeyHeader = s; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration d) { timeout = d; }
    }

    public Connection lookup(Route route) {
        Connection connection = connections.get(route.connection());
        if (connection == null) throw new CommerceException("CONNECTION_UNKNOWN", null, "Connection não registrada: " + route.connection());
        validateBase(connection.getBaseUrl());
        if (connection.getTimeout() == null || connection.getTimeout().isNegative() || connection.getTimeout().isZero())
            throw new CommerceException("CONNECTION_INVALID", null, "Timeout inválido");
        return connection;
    }
    public URI uri(Route route) {
        Connection connection = lookup(route);
        URI base = URI.create(connection.getBaseUrl());
        // Enforce origin-level base URLs; route path cannot replace the trusted host.
        return URI.create(base.toString() + route.path());
    }
    public static void validateBase(String value) {
        if (value == null) throw new CommerceException("CONNECTION_INVALID", null, "baseUrl ausente");
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))
                || value.endsWith("/")) throw new IllegalArgumentException("baseUrl deve ser origem HTTP sem path");
            if (!"https".equalsIgnoreCase(uri.getScheme())
                && !("http".equalsIgnoreCase(uri.getScheme()) && (host.equals("localhost") || host.equals("127.0.0.1"))))
                throw new IllegalArgumentException("HTTPS é obrigatório fora de localhost");
        } catch (IllegalArgumentException ex) {
            throw new CommerceException("CONNECTION_INVALID", null, "baseUrl inválida ou insegura", ex);
        }
    }
}
