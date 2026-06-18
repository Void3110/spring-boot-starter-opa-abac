package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.opaabac.security.resilience.CallGuard;
import dev.dmitriikonovalov.opaabac.security.resilience.Resilience4jCallGuard;
import dev.dmitriikonovalov.opaabac.security.resilience.ResilienceConfig;
import java.time.Duration;

/**
 * Small factory for the app-side cross-service HTTP edges' {@link CallGuard}s (Slice B3). The
 * production guards are wired as beans in {@link CatalogResilienceConfig} from
 * {@code opa.abac.resilience.resolve.*} / {@code …tag.*}; this helper provides the
 * <strong>disabled</strong> guard the edges' test/demo constructors use — a single unguarded attempt,
 * byte-identical to pre-B3 (so the existing edge unit tests keep their exact one-shot behavior).
 */
final class CallGuards {

    private CallGuards() {}

    /** A guard with resilience off: one unguarded attempt, no retry, no breaker. */
    static CallGuard disabled(String name) {
        ResilienceConfig off = new ResilienceConfig(
                false, 0, Duration.ofMillis(50), Duration.ofSeconds(1), 1, Duration.ofSeconds(1), 1);
        return new Resilience4jCallGuard(name, off);
    }
}
