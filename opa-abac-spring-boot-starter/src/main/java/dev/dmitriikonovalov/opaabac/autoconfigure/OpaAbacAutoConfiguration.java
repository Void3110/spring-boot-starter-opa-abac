package dev.dmitriikonovalov.opaabac.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Auto-configuration entry point for the OPA ABAC starter.
 *
 * <p>Currently a skeleton: it only binds {@link OpaAbacProperties}. Beans for the OPA client,
 * authorization manager, and context extractor are wired in as later phases land.
 */
@AutoConfiguration
@EnableConfigurationProperties(OpaAbacProperties.class)
@ConditionalOnProperty(prefix = "opa.abac", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpaAbacAutoConfiguration {
}
