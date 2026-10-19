package com.example.commerce.mapping;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SupplierMappingBootstrap implements ApplicationRunner {
    private final SupplierMappingRegistry registry;
    private final JsonMapper mapper;
    public SupplierMappingBootstrap(SupplierMappingRegistry registry, JsonMapper mapper) {
        this.registry = registry; this.mapper = mapper;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath*:suppliers/*.json");
        for (var resource : resources) {
            try (var in = resource.getInputStream()) {
                var v = registry.publish(mapper.readTree(in));
                registry.activate(v.supplierId(), v.version());
            }
        }
    }
}
