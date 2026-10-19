package com.example.commerce.integration;

import com.example.commerce.mapping.SupplierMappingRegistry.Route;
import io.modelcontextprotocol.client.McpSyncClient;

public interface McpClientProvider {
    McpSyncClient get(Route route);
}
