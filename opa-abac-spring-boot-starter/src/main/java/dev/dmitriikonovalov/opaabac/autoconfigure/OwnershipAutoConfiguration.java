package dev.dmitriikonovalov.opaabac.autoconfigure;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import dev.dmitriikonovalov.opaabac.security.ownership.DiscoveryOwnershipResolver;
import dev.dmitriikonovalov.opaabac.security.ownership.OwnershipProperties;
import dev.dmitriikonovalov.opaabac.security.ownership.ResourceOwnershipResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the cross-service ownership resolver (Slice B4, ADR 0019) — the {@link DiscoveryOwnershipResolver}
 * default impl over an {@code abac.ownership.*} config registry.
 *
 * <h2>Opt-in by property, fail-closed by absence</h2>
 * The resolver is registered <strong>only</strong> when {@code abac.ownership.enabled=true} (default off),
 * so a service that does not do self-service ownership checks (the catalog service) pays nothing, and a
 * service that does (the user-service's {@code createTeam}) opts in with one flag plus the
 * {@code services} registry. Consumers inject it via {@code ObjectProvider<ResourceOwnershipResolver>}:
 * when the resolver is <em>absent</em> (not opted in), the public {@code createTeam} path
 * <strong>fails closed</strong> (denies if it cannot verify) — exactly the safe default. When present but
 * the registry is empty, every type is unknown → not-owner → also deny. There is no configuration in which
 * absence or misconfiguration widens.
 *
 * <p>{@code @ConditionalOnMissingBean} so an adopter can supply their own {@link ResourceOwnershipResolver}
 * (a different discovery strategy, a local DB lookup) and keep winning.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "abac.ownership", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OwnershipProperties.class)
class OwnershipAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ResourceOwnershipResolver.class)
    ResourceOwnershipResolver discoveryOwnershipResolver(
            OwnershipProperties properties, ObjectProvider<ObjectMapper> objectMapper) {
        // Use the application's ObjectMapper when present — on Boot 4 that bean is Jackson 3's, so the
        // ObjectProvider matches the auto-configured mapper again; fall back to a plain one so the
        // starter wires cleanly even in a bare context.
        return new DiscoveryOwnershipResolver(properties, objectMapper.getIfAvailable(() -> JsonMapper.builder().build()));
    }
}
