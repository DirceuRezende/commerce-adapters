package com.example.commerce;

import com.example.commerce.mapping.SupplierCommerceGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CommerceApplicationTest {
    @Test void startsAndLoadsSupplierMappings() {
        try (var context = new SpringApplicationBuilder(CommerceApplication.class)
                .web(WebApplicationType.NONE).run()) {
            var registry = context.getBean(SupplierCommerceGateway.class);
            for (String supplier : new String[] {"rest-b", "graphql-c", "hubii"}) {
                assertNotNull(registry.active(supplier).operation("createCart"));
            }
        }
    }
}
