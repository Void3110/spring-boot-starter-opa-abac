package dev.dmitriikonovalov.example.mcp.config;

import dev.dmitriikonovalov.example.mcp.tool.CallerBearerSupplier;
import dev.dmitriikonovalov.example.mcp.tool.CatalogApiClient;
import dev.dmitriikonovalov.example.mcp.tool.CatalogApiErrorTranslator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Wires the tools' single outbound edge — the catalog REST client and its error vocabulary. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpServerProperties.class)
public class CatalogToolConfiguration {

    @Bean
    CallerBearerSupplier callerBearerSupplier() {
        return new CallerBearerSupplier();
    }

    @Bean
    CatalogApiErrorTranslator catalogApiErrorTranslator(ObjectMapper objectMapper) {
        return new CatalogApiErrorTranslator(objectMapper);
    }

    @Bean
    CatalogApiClient catalogApiClient(
            ObjectMapper objectMapper,
            CallerBearerSupplier callerBearerSupplier,
            CatalogApiErrorTranslator catalogApiErrorTranslator,
            McpServerProperties properties) {
        McpServerProperties.Catalog catalog = properties.getCatalog();
        return new CatalogApiClient(
                objectMapper,
                callerBearerSupplier,
                catalogApiErrorTranslator,
                catalog.getBaseUrl(),
                catalog.getConnectTimeout(),
                catalog.getReadTimeout());
    }
}
