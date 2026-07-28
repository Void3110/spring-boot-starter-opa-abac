package dev.dmitriikonovalov.example.mcp.config;

import dev.dmitriikonovalov.example.mcp.identity.AgentCapabilityProperties;
import dev.dmitriikonovalov.example.mcp.identity.AgentCapabilitySupplier;
import dev.dmitriikonovalov.example.mcp.identity.ConfigAgentCapabilitySupplier;
import dev.dmitriikonovalov.example.mcp.identity.TurnScopedCapabilityCache;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the capability seam: the demo's config-backed source, wrapped in the turn-scoped memo.
 *
 * <p>The memo decorates at <strong>bean level</strong>, so every consumer — the call-time gate and the
 * roster pre-flight alike — shares one answer per actor per turn. Decorating at the call site instead
 * would let the roster and the gate resolve independently and disagree within a single turn.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentCapabilityProperties.class)
public class AgentCapabilityConfiguration {

    @Bean
    AgentCapabilitySupplier agentCapabilitySupplier(AgentCapabilityProperties properties) {
        return new TurnScopedCapabilityCache(new ConfigAgentCapabilitySupplier(properties));
    }
}
