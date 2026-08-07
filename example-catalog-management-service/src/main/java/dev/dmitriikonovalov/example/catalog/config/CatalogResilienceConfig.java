package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.opaabac.autoconfigure.OpaAbacProperties;
import dev.dmitriikonovalov.opaabac.security.resilience.CallGuard;
import dev.dmitriikonovalov.opaabac.security.resilience.Resilience4jCallGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the app-side cross-service HTTP edges' resilience guards (Slice B3, ADR 0017) — the resolve, tag and
 * supervised {@link CallGuard}s the catalog's {@link HttpRoleDefinitionSupplier}, {@link TagDefinitionClient}
 * and {@link SupervisedScopeClient} run their exchanges through. Each guard is built from the starter's
 * {@link OpaAbacProperties} resilience tree ({@code opa.abac.resilience.resolve.*} / {@code …tag.*}), with
 * the master kill-switch folded in — so the <em>same</em> R4j, the <em>same</em> knobs, the <em>same</em>
 * config shape back every edge (the OPA decorator's guard is wired in the starter; these are necessarily app
 * code). Independent, per-endpoint breakers: a fault in {@code /internal/tag-definitions} or
 * {@code /internal/supervised-targets} cannot trip {@code /internal/effective-role}.
 *
 * <p>The OPA edge's resilience is provided by the library (the starter's {@code @ConditionalOnClass} R4j
 * decorator); these edges are the adopter's own HTTP clients, so the example app provides their guards
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
     * The supervised-scope-edge guard ({@code SupervisedScopeClient}, ADR 0029): the <em>same</em>
     * {@code resilience.resolve.*} budget — it is the same user-service, the same read-only GET shape — but
     * a <strong>separate breaker instance</strong>, deliberately. Sharing {@code resolveCallGuard} would let
     * a {@code /internal/supervised-targets} outage trip the breaker every persona's
     * {@code /internal/effective-role} resolution depends on, turning this slice's degrade-to-
     * membership-only into an empty page for everyone — the per-endpoint-breaker independence ADR 0017
     * already establishes for the resolve/tag pair.
     */
    @Bean
    CallGuard supervisedCallGuard(ObjectProvider<OpaAbacProperties> properties) {
        OpaAbacProperties.Resilience r = resilience(properties);
        return new Resilience4jCallGuard("supervised", r.getResolve().toConfig(r.isEnabled()));
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
