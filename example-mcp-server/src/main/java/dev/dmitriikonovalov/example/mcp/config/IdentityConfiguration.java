package dev.dmitriikonovalov.example.mcp.config;

import dev.dmitriikonovalov.opaabac.mcp.identity.ActorClaimWiringCheck;
import dev.dmitriikonovalov.opaabac.mcp.identity.ClaimDelegationChainExtractor;
import dev.dmitriikonovalov.opaabac.mcp.identity.DelegationChainExtractor;
import dev.dmitriikonovalov.opaabac.mcp.identity.IdentityProperties;
import dev.dmitriikonovalov.opaabac.autoconfigure.OpaAbacProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the dual-identity seam: the delegation-chain extractor plus the startup check that keeps its
 * claim name and the starter's copied-claims list from drifting apart.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentityConfiguration {

    @Bean
    DelegationChainExtractor delegationChainExtractor(
            ObjectMapper objectMapper, IdentityProperties properties) {
        return new ClaimDelegationChainExtractor(objectMapper, properties);
    }

    @Bean
    ActorClaimWiringCheck actorClaimWiringCheck(
            IdentityProperties identityProperties, OpaAbacProperties starterProperties) {
        return new ActorClaimWiringCheck(
                identityProperties, starterProperties.getSubject().getAttributeClaims());
    }
}
