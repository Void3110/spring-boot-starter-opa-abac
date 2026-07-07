package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.opaabac.autoconfigure.OpaAbacProperties;
import dev.dmitriikonovalov.opaabac.security.resilience.CallGuard;
import dev.dmitriikonovalov.opaabac.security.resilience.Resilience4jCallGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the app-side cross-service HTTP edges' resilience guards (Slice B3, ADR 0017) — the resolve and tag
 * {@link CallGuard}s the catalog's {@link HttpRoleDefinitionSupplier} and {@link TagDefinitionClient} run
 * their exchanges through. Each guard is built from the starter's {@link OpaAbacProperties} resilience tree
 * ({@code opa.abac.resilience.resolve.*} / {@code …tag.*}), with the master kill-switch folded in — so the
 * <em>same</em> R4j, the <em>same</em> knobs, the <em>same</em> config shape back all three edges (the OPA
 * decorator's guard is wired in the starter; these two are necessarily app code). Independent, per-endpoint
 * breakers: a fault in {@code /internal/tag-definitions} cannot trip {@code /internal/effective-role}.
 *
 * <p>The OPA edge's resilience is provided by the library (the starter's {@code @ConditionalOnClass} R4j
 * decorator); these two edges are the adopter's own HTTP clients, so the example app provides their guards
 * — the honest "uniform posture" the slice demonstrates (ADR 0017 §6).
 */
@Configuration
public class CatalogResilienceConfig {

    /** The resolve-edge guard: budget from {@code resilience.resolve.*}, master switch folded in. */
    @Bean
    CallGuard resolveCallGuard(ObjectProvider<OpaAbacProperties> properties) {
        OpaAbacProperties.Resilience r = resilience(properties);
        return new Resilience4jCallGuard("resolve", r.getResolve().toConfig(r.isEnabled()));
    }

    /** The tag-edge guard: budget from {@code resilience.tag.*}, master switch folded in. */
    @Bean
    CallGuard tagCallGuard(ObjectProvider<OpaAbacProperties> properties) {
        OpaAbacProperties.Resilience r = resilience(properties);
        return new Resilience4jCallGuard("tag", r.getTag().toConfig(r.isEnabled()));
    }

    /**
     * The starter's bound resilience tree — or the class's defaults when the starter is off
     * ({@code opa.abac.enabled=false}, the unguarded-baseline rig, ADR 0021 §2): the app's own HTTP
     * edges keep their guards (default budgets) rather than losing the resilience posture entirely.
     */
    private static OpaAbacProperties.Resilience resilience(ObjectProvider<OpaAbacProperties> properties) {
        return properties.getIfAvailable(OpaAbacProperties::new).getResilience();
    }
}
