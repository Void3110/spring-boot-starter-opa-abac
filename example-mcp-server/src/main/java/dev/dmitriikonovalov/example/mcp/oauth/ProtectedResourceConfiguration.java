package dev.dmitriikonovalov.example.mcp.oauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Builds the protected-resource document at startup, so a half-configured or malformed one fails the
 * context instead of being served (ADR 0035 §6).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProtectedResourceProperties.class)
public class ProtectedResourceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProtectedResourceConfiguration.class);

    /**
     * Eager even under {@code spring.main.lazy-initialization=true}: the contract is a startup failure for a
     * half-configured or malformed document, and a lazy bean would defer it to the first request.
     */
    @Bean
    @Lazy(false)
    ProtectedResourceDocument protectedResourceDocument(ProtectedResourceProperties properties) {
        ProtectedResourceDocument document = ProtectedResourceDocument.from(properties);
        if (!document.isEnabled()) {
            log.warn("Protected-resource discovery is OFF: {} and {} are unset, so {} and {} answer 404 and an "
                            + "MCP client cannot discover where to log a user in.",
                    ProtectedResourceDocument.RESOURCE_KEY, ProtectedResourceDocument.AUTHORIZATION_SERVER_KEY,
                    ProtectedResourceMetadataController.ROOT_PATH, ProtectedResourceMetadataController.MCP_PATH);
        }
        return document;
    }
}
